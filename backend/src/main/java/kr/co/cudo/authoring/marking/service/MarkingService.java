package kr.co.cudo.authoring.marking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.dto.MarkingResponse;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.marking.listener.MarkingBatchTriggerReport;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.sysconfig.dto.VerificationEventQuestionResponse;
import kr.co.cudo.authoring.sysconfig.service.VerificationEventQuestionResolver;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoDurationResolver;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 마킹 비즈니스 로직.
 *
 * <p>Phase 2 — VLM 콜백 요청 발송은 Phase 3 에서 구현 (TODO).
 *
 * <p><b>오케스트레이션 구조(HIGH/MEDIUM 수정):</b> 마킹 생성은 <b>비트랜잭션 오케스트레이션</b>
 * {@link #create(Long, MarkingRequest, TokenClaims)} 이 진입점이다. 순서는 ① 사전 인가·프리컨디션 확인
 * (프로브 이전, 값싼 readonly read) → ② (AUTO 일 때만) 영상 길이 해석(ffprobe, 쓰기 트랜잭션 밖) →
 * ③ persist(쓰기 트랜잭션)다. 이로써 고비용 ffprobe 는 <b>인가·프리컨디션 통과 이후에만</b> 실행되고
 * (CWE-862/400, OWASP API4), 여전히 쓰기 트랜잭션/커넥션 밖에서 수행된다(MEDIUM-1 커넥션 격리 유지).
 * 컨트롤러는 이 진입점에 얇게 위임할 뿐 도메인 분기를 갖지 않는다(레이어 원칙).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkingService {

    /** AUTO 마킹 모드 식별자 (요청 mode) — duration 해석 트리거 조건. */
    private static final String MODE_AUTO = "AUTO";

    /** MANUAL 마킹 모드 식별자 (요청 mode) — C-ISSUE-01 상한 검증에도 duration 이 필요하다. */
    private static final String MODE_MANUAL = "MANUAL";

    private final LsMarkingRepository markingRepository;
    private final VideoRepository videoRepository;
    private final LsTaskAssignmentRepository assignmentRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    /**
     * 단일 fps 소스 — 자동 마킹의 30fps 고정 가정(M-3)을 실 fps 로 대체.
     * 여기서 해석한 fps 를 마킹 레코드에 pin 하여 추출단계가 재조회 없이 동일 값을 쓰게 한다(TOCTOU 제거).
     */
    private final VideoFpsResolver fpsResolver;
    /**
     * 프로브 이전 사전 인가·프리컨디션 확인기(HIGH — CWE-862/400). 짧은 {@code REQUIRES_NEW} readonly
     * read 로 인가·프리컨디션을 먼저 거부해, 고비용 ffprobe 가 인가 통과 이후에만 실행되게 한다.
     */
    private final MarkingPrecheckReader precheckReader;
    /**
     * 영상 길이 해석기 — <b>쓰기 트랜잭션 진입 전</b>에 호출해 ffprobe 폴백까지 트랜잭션/커넥션 밖에서
     * 수행한다(MEDIUM-1 — HikariCP 풀 고갈 방지). AUTO 모드에서만, 그리고 <b>사전 인가·프리컨디션 통과
     * 이후에만</b> 트리거한다(HIGH — CWE-862/400).
     */
    private final VideoDurationResolver durationResolver;

    /**
     * 그 영상의 <b>검증 이벤트 유형</b> 조달처 — 관제 인입 원장({@code LS_DATA_INGEST.VRFC_EVNT_TYPE_CD}).
     * 질문은 유형별로 등록되므로 유형을 모르면 고를 축 자체가 없다.
     */
    private final IngestSourceRepository ingestSourceRepository;

    /**
     * 질문 조달 <b>단일 판정기</b> — 「고른 질문이 그 유형에 속하는가 / 아니면 첫 번째는 무엇인가」의
     * 해석은 오직 이 빈이 갖는다. 마킹·배치·어노테이션 세 소비자가 같은 질문을 봐야 하므로 여기서
     * 규칙을 복제하지 않는다(복제하는 순간 두 번째 진실원이 된다).
     */
    private final VerificationEventQuestionResolver questionResolver;

    /**
     * 자기 참조(트랜잭션 프록시) — 비트랜잭션 오케스트레이션 {@link #create(Long, MarkingRequest, TokenClaims)}
     * 가 쓰기 트랜잭션 persist {@link #create(Long, MarkingRequest, TokenClaims, Integer)} 를 <b>프록시 경유</b>로
     * 호출하도록 한다. 자기호출(this.create)은 프록시를 우회해 {@code @Transactional} 이 적용되지 않으므로
     * (persist 가 트랜잭션 없이 실행되어 AFTER_COMMIT 배치 브리지가 깨짐), 자기 주입 프록시로 우회한다.
     * 순환 주입이라 {@code @Lazy} 로 끊는다. 컨테이너 밖(순수 단위 테스트)에서는 null 이며, 그 경우
     * {@code this} 로 폴백한다(단위 테스트는 mock 리포지토리라 실제 트랜잭션이 불필요).
     */
    @Autowired
    @Lazy
    private MarkingService self;

    /**
     * 마킹 생성 — <b>비트랜잭션 오케스트레이션 진입점</b>.
     *
     * <p>순서(불변식): ① 사전 인가·프리컨디션 확인({@link MarkingPrecheckReader}) → ② AUTO 면 영상 길이
     * 해석({@link VideoDurationResolver}, 쓰기 트랜잭션 밖) → ③ persist({@link #create(Long, MarkingRequest,
     * TokenClaims, Integer)}, 쓰기 트랜잭션). ①에서 인가·프리컨디션 위반이면 <b>프로브를 트리거하기 전에</b>
     * 기존과 동일한 예외로 즉시 거부한다(HIGH — 미배정 WORKER 가 403 이전에 ffprobe 를 트리거하는 리소스
     * 소모 표면 제거).
     *
     * @param rawSn 영상 PK
     * @param req   마킹 생성 요청
     * @param actor 인증된 사용자
     * @return 생성된 마킹 응답
     */
    public MarkingResponse create(Long rawSn, MarkingRequest req, TokenClaims actor) {
        // DEV_FIX H11 — 스레드 로컬 잔여값 제거(스레드 풀 재사용 오염 방지). 배치 트리거 결과는
        //   AFTER_COMMIT 브리지가 같은 스레드에서 기록하고, persist 반환 직후 여기서 소비한다.
        MarkingBatchTriggerReport.begin();

        // 1. 사전 인가·프리컨디션(값싼 readonly read) — 프로브 이전에 확인해 위반 시 즉시 거부.
        //    이 read 는 짧은 REQUIRES_NEW 로 커넥션을 즉시 반납하므로 프로브 시점에 커넥션을 보유하지 않는다.
        precheckReader.precheck(rawSn, actor);

        // 2. 영상 길이 해석 — 사전 인가·프리컨디션 통과 이후에만(HIGH), 쓰기 트랜잭션 진입 전에 수행하므로
        //    ffprobe 폴백이 어떤 DB 커넥션도 보유하지 않는다(MEDIUM-1).
        //    C-ISSUE-01 — MANUAL 도 길이를 해석한다: 수동 마킹의 <b>상한 검증</b>(frameIndex < 총 프레임 수)에
        //    필요하기 때문이다. 단 MANUAL 은 <b>프로브 없는 DB 전용 해석</b>을 쓴다 — 대화형 동작에 ffprobe
        //    서브프로세스를 태우지 않는다는 기존 계약을 그대로 지킨다. 길이를 못 구하면(null) 상한 검증만
        //    건너뛰고 하한·중복은 그대로 적용한다(전부 스킵 금지).
        //    잘못된/누락 mode 는 null 로 두고 persist 가 INVALID_INPUT 으로 거부한다(기존 계약 보존).
        Integer durationSec = null;
        if (MODE_AUTO.equals(req.mode())) {
            durationSec = durationResolver.resolveDurationSec(rawSn);
        } else if (MODE_MANUAL.equals(req.mode())) {
            durationSec = durationResolver.resolveDurationSecWithoutProbe(rawSn);
        }

        // 3. persist(쓰기 트랜잭션) — self 프록시 경유(자기호출 프록시 우회 회피). 단위 테스트는 self=null → this.
        MarkingService target = (self != null) ? self : this;
        MarkingResponse response = target.create(rawSn, req, actor, durationSec);

        // 4. 배치 트리거 결과 반영 (DEV_FIX H11) — persist 트랜잭션이 커밋되면서 AFTER_COMMIT 브리지가
        //    같은 스레드에서 이미 실행됐다. 배치가 시작되지 않았다면 그 사실과 사유를 응답에 실어
        //    "201 인데 아무 일도 안 일어남"을 없앤다. 브리지 미실행(=판정 불가)이면 두 필드는 null.
        MarkingBatchTriggerReport.Outcome outcome = MarkingBatchTriggerReport.consume();
        if (outcome == null) {
            return response;
        }
        if (!outcome.triggered()) {
            log.warn("[Marking] batch not triggered rawSn={} markingSn={}", rawSn, response.markingSn());
        }
        return response.withBatchOutcome(outcome.triggered(), outcome.reason());
    }

    /**
     * 마킹 생성 — <b>쓰기 트랜잭션 persist</b> (오케스트레이션 {@link #create(Long, MarkingRequest, TokenClaims)}
     * 이 사전 인가·프리컨디션 통과 + 영상 길이 해석 후 호출).
     *
     * <p><b>방어적 이중화(defense-in-depth):</b> 인가·프리컨디션은 오케스트레이션의 사전확인
     * ({@link MarkingPrecheckReader})에서 이미 통과했지만, 트랜잭션 원자 source-of-truth 로서 여기서
     * <b>동일 규칙·순서로 다시 강제</b>한다({@link MarkingGuards} 로 단일화 — 두 지점의 계약 불변). 사전확인과
     * persist 사이에 상태가 변하더라도 최종 쓰기 시점 규칙이 보장된다. 이 재확인은 제거하지 않는다.
     *
     * <p><b>리소스 관리(MEDIUM-1 — ffprobe 를 쓰기 트랜잭션 밖에서):</b> AUTO 모드의 영상 길이는
     * 오케스트레이션이 <b>이 트랜잭션 진입 전</b>에 {@link VideoDurationResolver}(트랜잭션-외, 프로브가
     * 커넥션 미보유)로 해석해 {@code autoDurationSec} 로 주입한다. 따라서 본 쓰기 트랜잭션은 프로브를
     * 트리거하지 않는다.
     *
     * @param rawSn           영상 PK
     * @param req             마킹 생성 요청
     * @param actor           인증된 사용자
     * @param autoDurationSec AUTO 모드용으로 <b>트랜잭션 밖에서 미리 해석된</b> 영상 길이(초). MANUAL 모드에서는
     *                        사용되지 않으며 null 이어도 무방하다. AUTO 인데 null(전 경로 해석 실패)이면
     *                        {@link #generateAutoMarks} backstop 이 {@link ErrorCode#INVALID_INPUT} 로 거부한다.
     * @return 생성된 마킹 응답
     */
    @Transactional("controlTransactionManager")
    public MarkingResponse create(Long rawSn, MarkingRequest req, TokenClaims actor, Integer autoDurationSec) {
        // 0. 인가 재확인 (CWE-639 수평 권한 상승 차단) — 사전확인과 동일 규칙(방어적 이중화).
        MarkingGuards.requireAssignedOrReviewer(rawSn, actor, assignmentRepository);

        // 1. 영상 존재 + 프리컨디션 재확인 (비식별 완료·MARKING_READY·이벤트 유형) — 사전확인과 동일 규칙·순서.
        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
        MarkingGuards.requirePreconditions(raw);

        // 1-2. 활성 마킹 중복 재확인 (B-ISSUE-22) — 사전확인과 동일 규칙. 순차 요청은 여기서 409 로
        //       거부되고, 동시 요청은 아래 flush 시점의 DB 부분 유니크 인덱스(V142)가 잡는다.
        MarkingGuards.requireNoActiveMarking(rawSn, markingRepository);

        // 1-3. 이벤트 유형 코드 조달 — 영상의 EVNT_TYPE_CD 를 그대로 쓴다(존재 검증은 requirePreconditions
        //       가 이미 수행). ★ 이 값은 마킹 행에 저장하지 않고 <응답에만> 실린다(V27) — 마킹 행에
        //       베껴 두면 영상 쪽이 바뀔 때 두 값이 어긋난다. [design: ERD-013]
        String eventName = raw.getEvntTypeCd();

        // 2. 마킹 시점에 실 fps 를 확정(pin) — TOCTOU 제거의 핵심.
        //    M-3 이전에는 자동마킹과 프레임추출이 각자 다른 시점에 resolveFps 를 재조회했다. Phase 2 의
        //    video.fps 메타 적재(ingest AFTER_COMMIT @Async)가 두 시점 사이에 완료되면, 마킹은 30 폴백으로
        //    frameIndex 를, 추출은 실 fps 로 seekMillis 를 계산해 프레임이 어긋났다(TOCTOU).
        //    이제 마킹 생성 시 해석한 fps 를 마킹 레코드에 저장하고, FfmpegFrameExtractor 가 재조회 대신
        //    이 pin 값을 읽어 계산하므로 마킹↔추출이 구조적으로 동일 값을 사용한다(정합성 불변식).
        //    미상 시 30.0 폴백이라 기존 동작과 동일(무회귀). AUTO 는 marks 산출에도 이 fps 를 쓴다.
        double fps = fpsResolver.resolveFps(rawSn);

        // 3. 마킹 모드에 따른 처리
        String marksJson;
        if ("AUTO".equals(req.mode())) {
            if (req.intervalFrames() == null || req.intervalFrames() <= 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "자동 모드에서 intervalFrames 는 1 이상이어야 합니다.");
            }
            // durationSec 은 오케스트레이션이 이 트랜잭션 진입 전에 해석해 주입한다(VDO_LEN_SEC → video.duration_ms
            // 메타 → 직접 프로브 폴백). FIX A — 적재 시 길이가 비어 자동 마킹만 INVALID_INPUT 으로 실패하던
            // 결함 제거. MEDIUM-1 — ffprobe 폴백은 이 쓰기 트랜잭션 밖(커넥션 미보유)에서 수행된다(위 Javadoc).
            marksJson = generateAutoMarks(autoDurationSec, req.intervalFrames(), fps);
        } else if (MODE_MANUAL.equals(req.mode())) {
            if (req.marks() == null || req.marks().isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "수동 모드에서 marks 는 필수입니다.");
            }
            // C-ISSUE-01 — 요청 내 중복 시점 + 영상 길이 기반 상한 검증(하한·형식은 DTO @Valid 가 이미 거름).
            validateManualMarks(req.marks(), autoDurationSec, fps, rawSn);
            marksJson = serializeMarks(req.marks());
        } else {
            throw new CustomException(ErrorCode.INVALID_INPUT, "mode 는 AUTO 또는 MANUAL 이어야 합니다.");
        }

        // 3-2. 검증 이벤트 질문 선택값 해석 — 화면 입력을 그대로 신뢰하지 않는다. [design: AC-028]
        //      요청값이 그 영상의 검증 이벤트 유형에 속하면 그대로, 아니면(또는 미선택이면) 그 유형의
        //      첫 번째 질문으로 되돌아간다. 유형이 미수신이거나 질문이 0건이면 null 이며 그래도 마킹은
        //      막지 않는다 — 질문 부재는 거부 사유가 아니다(위탁도 그대로 나간다).
        Long questionSn = resolveVerificationQuestionSn(rawSn, req.vrfcEvntQstnSn());

        // 4. Entity 생성 + 저장 — 해석한 fps 를 마킹에 pin 하여 추출단계가 재조회 없이 동일 값을 사용하게 한다.
        //    ★ 생성자 식별자는 토큰 주체를 <문자 그대로> 담는다(V27). 숫자로 파싱해 담던 구 방식은
        //      포털 채널의 비숫자 주체를 조용히 null 로 떨어뜨려 소유자 없는 마킹을 만든다.
        //    ★ 이벤트 유형 코드·영상 경로는 더 이상 마킹 행에 베끼지 않는다(V27) — 응답에서 영상 행의
        //      값을 그대로 실어 계약을 유지한다(아래 5).
        String actorNo = MarkingGuards.creatorId(actor.sub());
        LsMarking marking = "AUTO".equals(req.mode())
                ? LsMarking.createAuto(rawSn, req.intervalFrames(), marksJson, actorNo, fps, questionSn)
                : LsMarking.createManual(rawSn, marksJson, actorNo, fps, questionSn);
        // 동시성 최종 방어(B-ISSUE-22 / CWE-362) — 부분 유니크 인덱스(V142) 위반을 <b>이 메서드 안에서</b>
        //   표면화해 409 로 변환한다. save/flush 를 함께 감싸는 이유:
        //   - MARKING_SN 이 IDENTITY 라 {@code save} 시점에 INSERT 가 즉시 실행된다(위반이 여기서 터진다).
        //   - 그럼에도 flush 를 함께 호출하는 것은 구현 세부(쓰기 지연 여부)에 의존하지 않기 위함이다.
        //     둘 중 어디서 터지든 잡히지 않으면 커밋 시점(메서드 반환 이후)에 터져 500 이 나간다.
        //   PostgreSQL 은 유니크 위반 시 트랜잭션 전체를 abort 하므로 같은 tx 안에서 재시도하지 않고
        //   그대로 409 로 변환·롤백한다(부분 저장 없음).
        try {
            markingRepository.save(marking);
            markingRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("[Marking] concurrent duplicate rejected rawSn={}", rawSn);
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 진행 중인 마킹이 있습니다. 기존 마킹이 종결된 뒤 다시 시도하세요.", e);
        }

        log.info("[Marking] created rawSn={}, mode={}, markingSn={}", rawSn, req.mode(), marking.getMarkingSn());

        eventPublisher.publishEvent(new MarkingCompletedEvent(rawSn, marking.getMarkingSn()));

        // 5. 응답 — 이벤트 유형 코드·영상 경로는 이미 읽어 둔 영상 행에서 조달한다(마킹 행에 없다).
        //    영상 행을 다시 조회하지 않으므로 추가 질의가 없다.
        return MarkingResponse.from(marking, objectMapper, eventName, raw.getRawFilePathNm());
    }

    /**
     * 마킹이 보관할 <b>검증 이벤트 질문 일련번호</b>를 해석한다. [design: AC-028 · ERD-013]
     *
     * <h3>왜 요청값을 그대로 쓰지 않는가</h3>
     * <p>{@code LS_MARKING.VRFC_EVNT_QSTN_SN} 에는 <b>물리 FK 가 없다</b>(V17) — 질문 목록이 관리 화면에서
     * 전체 교체로 저장되어 가리키던 행이 사라지는 것이 정상 동선이기 때문이다. 참조 무결성을 DB 가 아니라
     * 판정기가 가지므로, 저장 시점에도 <b>그 유형에 속하는 값인지</b> 확인해 어긋나면 첫 번째 질문으로
     * 되돌린다. 이는 화면 입력 불신뢰(CWE-20) 이기도 하다.
     *
     * <h3>★ 어긋난 값은 거부가 아니라 교정이다</h3>
     * <p>400 으로 되돌려주지 않는다. 목록이 그 사이에 교체됐을 뿐인 정상 동선이 사용자에게는 원인 불명의
     * 실패로 보이고, 질문 하나 때문에 마킹과 잔여 배치가 막히기 때문이다.
     *
     * @param rawSn          영상 PK
     * @param requestedQstnSn 요청이 실어 온 선택값 (nullable — 미선택)
     * @return 해석된 질문 일련번호. 검증 이벤트 유형 미수신·그 유형의 질문 0건이면 {@code null}
     */
    private Long resolveVerificationQuestionSn(Long rawSn, Long requestedQstnSn) {
        String vrfcEvntTypeCd = resolveVrfcEvntTypeCd(rawSn);
        if (vrfcEvntTypeCd == null) {
            // 유형이 없으면 고를 축이 없다 — 비워 둔다(지어내지 않는다). 마킹은 그대로 진행한다.
            log.info("[Marking] verification event type missing — question left empty rawSn={}", rawSn);
            return null;
        }
        return questionResolver.resolve(requestedQstnSn, vrfcEvntTypeCd)
                .map(VerificationEventQuestionResponse::vrfcEvntQstnSn)
                .orElse(null);
    }

    /**
     * 그 영상의 검증 이벤트 유형 코드 조달 — 관제 인입 원장값을 읽는다.
     *
     * <p>정규화는 인입 엔티티의 {@link LsDataIngest#normalizeVrfcEvntType(String)} <b>한 함수</b>를
     * 재사용한다(리터럴 복제 금지 — 규칙이 갈리면 인입이 실어 보낸 표기가 여기서만 조달에 실패한다).
     * 영상 행이 없거나 인입 행이 없으면 {@code null} 이며 그것이 정상 경로다.
     */
    private String resolveVrfcEvntTypeCd(Long rawSn) {
        IngestSourceRow source = ingestSourceRepository.findSourceMeta(rawSn);
        String raw = source == null ? null : source.getVrfcEvntTypeCd();
        return LsDataIngest.normalizeVrfcEvntType(raw);
    }

    /**
     * 자동 모드: durationSec·실 fps 기반 intervalFrames 간격으로 marks 자동 생성 (프레임 단위).
     *
     * <p><b>M-3 수정 — 실 fps 사용:</b> 과거에는 영상의 실제 FPS 를 조회하지 않고 30fps 로 고정 가정하여
     * totalFrames·타임스탬프를 계산했다. NIA export Phase 2 가 {@code LS_DATA_META.video.fps} 에 실
     * 프레임레이트를 적재하므로, 이제 {@link VideoFpsResolver#resolveFps(Long)} 가 해석한 실 fps 를 인자로
     * 받아 계산한다. fps 미상 시 resolver 가 30.0 으로 폴백하므로 기존 동작과 동일하다(무회귀).
     *
     * <p><b>반올림 정책:</b> 분수 fps(예: 29.97) 를 지원하기 위해 double 로 계산하되,
     * {@code totalFrames = Math.round(durationSec × fps)} 로 프레임 총수를 반올림한다. 타임스탬프는
     * {@code frameIndex / fps}(초)를 정수 초로 절단(mm:ss 표기)한다. 추출 단계의 seekMillis 도 동일
     * fps·동일 Math.round 정책을 쓰므로 마킹↔추출이 정합한다.
     *
     * <p>경계/널 처리:
     * <ul>
     *   <li>{@code durationSec} 이 null 이거나 0 이하면 영상 길이를 알 수 없어 자동 마킹을 생성할 수 없으므로
     *       {@link ErrorCode#INVALID_INPUT} 로 거부한다(과거: totalFrames=0 으로 frame 0 단 1건만 생성되는 퇴화 방지).</li>
     *   <li>루프 상한은 {@code frameIndex < totalFrames} — totalFrames(예: 30fps·10초=300)는 존재하지 않는
     *       끝 경계 프레임이므로 포함하지 않는다(off-by-one 수정).</li>
     * </ul>
     *
     * @param durationSec    영상 길이 (초) — null/0 이하면 거부
     * @param intervalFrames 프레임 간격 (1 이상)
     * @param fps            영상 실 프레임레이트 (미상 시 호출자가 폴백값 30.0 을 전달) — 양수
     */
    public String generateAutoMarks(Integer durationSec, int intervalFrames, double fps) {
        if (durationSec == null || durationSec <= 0) {
            // FIX A backstop — durationSec·메타·직접 프로브까지 모두 실패한 진짜 예외 케이스만 여기 도달한다.
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이를 확인할 수 없어 자동 마킹을 생성할 수 없습니다.");
        }
        List<MarkItem> marks = new ArrayList<>();
        // M-3 수정 — 실 fps(video.fps, 미상 시 30.0 폴백)로 totalFrames 를 반올림 계산(분수 fps 지원).
        int totalFrames = (int) Math.round(durationSec * fps);
        for (int frameIndex = 0; frameIndex < totalFrames; frameIndex += intervalFrames) {
            double sec = frameIndex / fps;
            String timestamp = formatTimestamp((int) sec);
            marks.add(new MarkItem(frameIndex, timestamp));
        }
        return serializeMarks(marks);
    }

    /**
     * C-ISSUE-01 — MANUAL 마킹 항목 검증 (CWE-20).
     *
     * <ul>
     *   <li><b>중복 시점</b>: 같은 요청 안에 동일 {@code frameIndex} 가 2회 이상이면 400. 같은 프레임을 두 번
     *       마킹하는 것은 의미가 없고, 그대로 저장되면 프레임 추출·VLM 콜백이 같은 시점을 중복 처리한다.</li>
     *   <li><b>상한</b>: {@code frameIndex} 는 영상의 총 프레임 수(= round(길이×fps)) 미만이어야 한다.
     *       존재하지 않는 프레임을 마킹하면 추출 단계가 빈 프레임을 만들거나 조용히 실패한다.</li>
     *   <li><b>하한(0 이상)·타임스탬프 형식</b>은 {@link MarkItem} 의 Bean Validation 이 400 으로 거른다.</li>
     * </ul>
     *
     * <p><b>길이 미상 정책</b>: {@code LS_DATA_RAW.VDO_LEN_SEC} 가 비어 있고 메타·직접 프로브까지 실패하는
     * 영상이 실재한다(VideoDurationResolver 3단 폴백이 존재하는 이유). 이때 <b>상한 검증만</b> 건너뛰고
     * 중복·하한 검증은 그대로 적용한다(전부 스킵 금지). 건너뛴 사실은 WARN 으로 드러낸다 —
     * "검증 불가"가 조용히 "검증 통과"가 되지 않도록.
     */
    public void validateManualMarks(List<MarkItem> marks, Integer durationSec, double fps, Long rawSn) {
        Set<Integer> seen = new HashSet<>();
        for (MarkItem mark : marks) {
            Integer frameIndex = mark.frameIndex();
            if (frameIndex == null) {
                // DTO @NotNull 백스톱 — @Valid 미적용 경로(직접 서비스 호출)에서도 계약을 지킨다.
                throw new CustomException(ErrorCode.INVALID_INPUT, "frameIndex 는 필수입니다.");
            }
            if (frameIndex < 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "frameIndex 는 0 이상이어야 합니다.");
            }
            if (!seen.add(frameIndex)) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "중복된 마킹 시점입니다: frameIndex=" + frameIndex);
            }
        }
        if (durationSec == null || durationSec <= 0 || fps <= 0) {
            log.warn("[Marking] duration unknown — manual mark upper-bound check skipped rawSn={} marks={}",
                    rawSn, marks.size());
            return;
        }
        int limit = manualFrameIndexLimit(durationSec, fps);
        for (MarkItem mark : marks) {
            if (mark.frameIndex() >= limit) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "영상 길이를 벗어난 마킹 시점입니다: frameIndex=" + mark.frameIndex()
                                + " (허용 상한 " + limit + " 프레임 미만)");
            }
        }
    }

    /**
     * DEV_FIX(H10) — MANUAL 마킹 {@code frameIndex} 의 <b>배타 상한</b>(이 값 미만이어야 통과).
     *
     * <h3>왜 단순히 round(길이×fps) 가 아닌가 — 두 가지 오차원</h3>
     * <ol>
     *   <li><b>fps 불일치(주 원인)</b>: FE 가 30fps 를 하드코딩해 {@code frameIndex = round(t×30)} 을
     *       만들던 동안 서버 상한은 실 fps 로 계산돼, 25fps 영상이면 60초 상한 1500 vs FE 의 55초 마킹
     *       1650 → <b>영상 뒤 16.7% 구간을 마킹할 수 없었다</b>. 근본 수정은 FE 가 서버가 내려준 실 fps
     *       ({@code VideoDetailResponse.fps}, 같은 {@code VideoFpsResolver} 값)를 쓰는 것이고, 이 메서드는
     *       그 위의 2차 방어다.</li>
     *   <li><b>길이의 정수 초 절단</b>: {@code VDO_LEN_SEC}·{@code duration_ms→초} 는 정수 초로
     *       반올림되어 저장된다. 실제 60.4초 영상이 60 으로 기록되면 마지막 0.4초(30fps 기준 12프레임)의
     *       정상 마킹이 거부된다.</li>
     * </ol>
     * 그래서 상한에 <b>1초(=ceil(fps) 프레임) 마진</b>을 둔다. 마진의 목적은 "존재하지 않는 프레임을
     * 마킹해 추출 단계가 빈 프레임을 만드는 것"을 막는 것이므로, 영상 길이를 크게 벗어난 값
     * (실측 결함이던 {@code frameIndex=999999999})은 여전히 400 으로 거부된다.
     */
    static int manualFrameIndexLimit(int durationSec, double fps) {
        return (int) Math.round(durationSec * fps) + (int) Math.ceil(fps);
    }

    /**
     * marks 목록을 JSON 직렬화.
     */
    String serializeMarks(List<MarkItem> marks) {
        try {
            return objectMapper.writeValueAsString(marks);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "marks 직렬화 실패");
        }
    }

    /**
     * 초 단위를 "mm:ss" 형식으로 변환.
     */
    private String formatTimestamp(int totalSec) {
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return String.format("%02d:%02d", min, sec);
    }
}
