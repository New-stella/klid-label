package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.eventtype.service.EventTypeAutoRegistrar;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 인입 1건 적재의 트랜잭션 경계 빈 — 관제가 {@code LS_DATA_INGEST} 에 INSERT 한 행을
 * {@code LS_DATA_RAW} 로 옮기고 비식별 선두 파이프라인을 트리거한다.
 *
 * <p>각 행을 {@link Propagation#REQUIRES_NEW} 독립 트랜잭션({@code controlTransactionManager})으로
 * 적재한다 — 한 행의 JPA 예외(예: {@link DataIntegrityViolationException})로 트랜잭션이 rollback-only
 * 마킹되더라도 그 롤백이 해당 행 트랜잭션에만 한정되어, 같은 스캔의 다른 행 적재(커밋)를 오염시키지
 * 않는다. (private 메서드는 프록시 미적용이므로 별도 빈으로 분리한다.)
 *
 * <h3>트랜잭션 규약 (설계 §6-0 — 구속)</h3>
 * <ol>
 *   <li><b>클레임이 최상단</b> — 진입 직후 <b>식별자 가드보다 먼저</b>
 *       {@link LsDataIngestRepository#claimForProcessing} 을 호출하고 반환이 1 이 아니면 즉시
 *       skip 한다. Quartz 클러스터링은 <b>트리거 중복 발화만</b> 막으므로, 잡 내부 레이스(같은 후보
 *       목록을 받은 두 실행)의 방어는 이 원자 클레임뿐이다(CWE-362).</li>
 *   <li><b>엔티티는 이 트랜잭션 안에서 재조회</b> — 스캔({@code readOnly}) 세션이 넘긴 인스턴스는
 *       이 트랜잭션의 영속성 컨텍스트에 없어 {@code markDone}/{@code markFailed} 가 <b>dirty
 *       checking 대상이 아니다</b>(DB 는 PROCESSING 인데 종결이 안 찍혀 영구 좀비). 상태 전이는
 *       {@code findById} 로 다시 읽은 인스턴스에만 적용한다.</li>
 *   <li><b>착수 판정은 클레임 반환값으로만</b> — 클레임은 네이티브 UPDATE 라 영속성 컨텍스트를
 *       우회하므로 로드된 엔티티의 {@code prcsSttsCd} 는 {@code PENDING} 인 채 stale 이다.</li>
 * </ol>
 *
 * <h3>적재 매핑 ({@code LS_DATA_INGEST} → {@code LS_DATA_RAW})</h3>
 * <ul>
 *   <li>vmsClipId ← {@code VMS_CLIP_ID}(UK 멱등키) · vmsCctvId ← {@code VMS_CCTV_ID}</li>
 *   <li>rawFilePathNm ← {@code RAW_FILE_PATH_NM}(관제 NAS 절대경로, 허용 루트 검증 후 원문 그대로)</li>
 *   <li>shtDt ← {@code SHT_DT} · durationSec ← {@code VDO_LEN_SEC}(<b>이미 초 단위</b>)</li>
 *   <li>lclgvCd ← {@code LCLGV_CD} — 관제 완료통지 페이로드 {@code lclgv_cd}(required)의 출처</li>
 *   <li>srcType ← {@code SRC_TYPE}(allowlist 통과분만) · prvcTypeCd = <b>{@code PRVC}</b>
 *       (관제 미제공 — fail-closed 기본값, {@link #DEFAULT_PRVC_TYPE} 참조)</li>
 *   <li>evntTypeCd ← <b>인입 {@code EVNT_TYPE_CD}(V166) 단독</b>. <b>관제가 안 보내면 null 로
 *       적재하고 적재는 성공</b>시키며, 그 영상은 마킹 단계에서 기존 가드가 막는다.
 *       상세는 {@link #resolveEvntTypeCd}.
 *       <p><b>구 서술 폐기 ①</b>: "evntTypeCd = 항상 null · 사용처는 인입 행을 참조한다"는 더 이상
 *       사실이 아니다. 그 구현은 마킹 프리컨디션과 충돌해 관제 인입 적재분의 <b>자동마킹을 100%
 *       400 으로 실패</b>시켰다(2026-08-04).
 *       <p><b>구 서술 폐기 ②</b>: "인입에 유형코드 컬럼이 없다"도 더 이상 사실이 아니다 — V166 이
 *       {@code LS_DATA_INGEST.EVNT_TYPE_CD} 를 신설했다.
 *       <p><b>구 서술 폐기 ③</b>: {@code EVNT_ID} 로 관제 공유 이벤트리스트를 조인해 해석하던
 *       <b>과도기 폴백은 제거됐다</b>(V167 — 그 테이블 자체가 없다). 유형코드의 단일 원천은 인입
 *       평면값이다.</li>
 * </ul>
 *
 * <h3>★ 이름·포맷·좌표·개인정보 3필드는 {@code LS_DATA_RAW} 로 복사하지 않는다</h3>
 * <p>{@code CCTV_NM}·{@code EVNT_NM}·{@code LCLGV_NM}·{@code FILE_FMT}·좌표·개인정보 3필드는
 * <b>{@code LS_DATA_INGEST} 가 단일 진실원</b>이며 조회 시 조인으로 읽는다. 관제가 준 <b>읽기 전용
 * 사실</b>을 작업 대상 마스터({@code LS_DATA_RAW} — 상태 전이·라벨링·검수가 붙는 가변 테이블)에
 * 복사하면 같은 값이 두 곳에 생기고, 수정될 일이 없는 값에 대해 이중 저장소를 유지하게 된다.
 * <p>조인이 성립하는 근거: <b>원본 영상은 항상 인입 행을 보유</b>하고(dev 내부 업로드도
 * {@code InternalUploadIngestWriter} 가 인입 행을 만든다) <b>인입 행은 영구 보존</b>된다. 파생영상만
 * 자기 인입 행이 없는데 <b>파생 깊이가 1 로 고정</b>(확정 정책)이라
 * {@code COALESCE(r.ORGNL_RAW_SN, r.RAW_SN)} 1단계 폴백으로 충분하다(재귀 불필요).
 * <p><b>예외 — {@code EVNT_TYPE_CD} 만 복사한다</b>: 마킹 프리컨디션({@code MarkingGuards})이
 * {@code LS_DATA_RAW} 의 이 컬럼을 <b>직접</b> 읽으므로 조인으로 대체할 수 없다.
 *
 * <h3>이벤트유형 자동등록 (V168)</h3>
 * <p>적재 시점에 관제가 보낸 {@code EVNT_TYPE_CD}/{@code EVNT_NM}/{@code EVNT_CLSF_CD} 로
 * 저작도구 소유 마스터({@code LS_EVNT_TYPE})에 <b>미등록 유형만</b> 등록한다
 * ({@link EventTypeAutoRegistrar}). 구 구현은 관제 공유 마스터 2종을 조회만 했기 때문에 관제가
 * 새 유형을 쓰기 시작하면 필터·라벨에서 <b>영영 보이지 않았다</b>.
 *
 * <h3>인입 행 종결 규칙 (R4)</h3>
 * <table><tr><th>상황</th><th>전이</th><th>이유</th></tr>
 *   <tr><td>적재 성공 / 기적재 확인</td><td>{@code markDone(rawSn)}</td><td>역추적 근거 보존</td></tr>
 *   <tr><td>파일 미도착 — 대기 상한 이내</td><td>{@code revertToPendingForRetry}</td>
 *       <td><b>실패가 아니다</b> — {@code FAILED} 면 폴링 술어에서 빠져 영구 미적재,
 *           방치하면 영구 좀비. 재시도횟수도 올리지 않는다.</td></tr>
 *   <tr><td>파일 미도착 — 대기 상한 초과</td><td>{@code markFailed}</td>
 *       <td><b>보류에는 끝이 있어야 한다</b>(설계 §6-0-1 ①) — 아래 참조</td></tr>
 *   <tr><td>식별자 blank · 허용 루트 밖 경로</td><td>{@code markFailed}</td>
 *       <td>재시도해도 통과할 수 없는 영구 사유</td></tr>
 * </table>
 *
 * <h3>미도착 대기 상한 + backoff (설계 §6-0-1 ① / §6-0-1-a — head-of-line blocking 차단)</h3>
 * <p>후보 조회는 {@code PENDING} + {@code RCPTN_DT ASC} + tick 상한이다. 미도착 복귀 행은 가장 오래된
 * 수신일시를 가지므로 <b>매 tick 앞자리를 다시 점유</b>하고, 상한만큼 쌓이면 그 뒤 정상 인입은
 * <b>영원히 픽업되지 않는다</b>. 미도착 판정은 NAS 권한 오류·깨진 심링크에서도 나오므로 "곧 해소된다"는
 * 전제가 성립하지 않는다. 두 장치로 함께 닫는다.
 * <ol>
 *   <li><b>대기 예산 상한</b> — 경과가 {@code authoring.control.training-scan.not-arrived-timeout-hours}
 *       를 넘으면 사유를 남기고 {@code FAILED} 로 내려 큐를 비운다(가역 — 재큐로 되살린다).</li>
 *   <li><b>재시도 예정 시각(backoff)</b> — 미도착 관측마다 {@code NXTM_RTRY_DT} 를 뒤로 밀어 그 행을
 *       폴링 후보에서 뺀다. 상한만 있으면 무한 정지가 <b>최대 상한(기본 24h) 정지</b>로 유계화될 뿐이라
 *       그동안 뒤의 정상 인입이 굶는다.</li>
 * </ol>
 * <p><b>예산의 시계는 우리 것이다</b>(설계 §6-0-1-a ㉠) — 경과는 관제가 준 {@code RCPTN_DT} 가 아니라
 * <b>우리가 스탬프한 최초 미도착 관측 시각({@code PRCS_DT})</b> 기준으로 잰다. {@code RCPTN_DT} 는
 * {@code DEFAULT CURRENT_TIMESTAMP} 일 뿐 강제가 없고 <b>INSERT 주체가 관제</b>라, 과거 시각이 명시
 * INSERT 되면 <b>파일이 도착하기도 전에 첫 픽업에서 종결</b>된다(다른 관제 수신값은 전부 검증하면서
 * 여기만 무검증으로 신뢰할 이유가 없다).
 * <p>판정축이 <b>재시도 횟수가 아니라 경과 시간</b>인 이유: {@code RTY_CNT} 는 <b>실패 이력 전용</b>이며
 * 대기 카운터로 전용하면 실패 원인 추적이 불가능해진다(엔티티 javadoc 과 동일 규칙).
 * <p>이 종결은 <b>가역</b>이다 — {@link LsDataIngestRepository#requeueFailedForRetry} 로 재큐하면 다음
 * 스캔이 다시 집는다(설계 §6-0-1 ② — 되돌릴 수 없는 차단과 끝나지 않는 보류는 둘 다 가용성 결함이다).
 *
 * <p>인입 행은 <b>영구 보존</b>한다(감사 추적) — 어떤 경로에서도 삭제하지 않는다.
 */
@Slf4j
@Component
public class TrainingVideoIngestTx {

    /**
     * 개인정보 처리 유형 기본값 — <b>{@code PRVC}(fail-closed)</b>. 2026-07-31 사용자 확정.
     *
     * <p><b>관제는 이 값을 보내지 않는다</b> — 관제팀 확인 결과 개인정보유형은 실제로 채워지지 않으며,
     * 그래서 {@code LS_DATA_INGEST} 에는 컬럼조차 없다(설계 R6 — 인입에는 관제가 보내는 값만 둔다).
     * 따라서 인입 경로의 <b>모든</b> 영상이 이 기본값으로 적재된다. 입력이 없을 때의 안전측은
     * "개인정보가 있고 익명처리되지 않은 원천영상"이므로 {@code ANONY}(개인정보 없음)가 아니라
     * {@code PRVC} 로 둔다.
     *
     * <p><b>{@code ANONY} 로 두면 안 되는 이유</b> — 값이 없다는 사실이 두 곳에서 <b>"개인정보 없음"이라는
     * 거짓 주장</b>으로 굳는다.
     * <ol>
     *   <li>export 의 {@code privacy_included} 가 {@code N} 으로 나가 학습데이터 수요자에게 잘못 전달된다.</li>
     *   <li>{@link LsDataRaw#needsDeidentify()} 가 false 라 {@code FrameImageService} 의
     *       "ANONY + 비식별 미준비 → <b>원본 폴백</b>" 분기가 열려 <b>마스킹 전 원본 프레임이 서빙</b>된다
     *       (CWE-359).</li>
     * </ol>
     *
     * <p><b>의도된 회귀</b>: {@code PRVC} 면 {@code needsDeidentify()} 가 true 라 비식별본이 없는 동안
     * 프레임 조회가 404 로 닫힌다 — 원본 노출을 막는 fail-closed 다. 관제를 거치지 않고 이미 익명·가명
     * 처리되어 들어오는 영상은 별도 경로에서 값을 지정하므로 이 기본값의 영향을 받지 않는다.
     */
    private static final String DEFAULT_PRVC_TYPE = LsDataRaw.PRVC_TYPE_PRVC;

    /** 미도착 대기 상한 하한(시간) — 0·음수 오설정이 전량 즉시 종결로 이어지지 않게 clamp 한다. */
    private static final long MIN_NOT_ARRIVED_TIMEOUT_HOURS = 1L;

    /**
     * 미도착 backoff 하한 — 첫 관측 직후에도 최소 이만큼은 폴링 후보에서 빠진다.
     *
     * <p>스캔 주기(기본 60초)보다 짧으면 backoff 가 사실상 없는 것과 같아 고착 행이 매 tick 앞자리를
     * 다시 점유한다. 반대로 너무 길면 <b>정상적인 파일 복사 지연</b>(수 초~수십 초)의 적재가 그만큼
     * 늦어지므로 스캔 주기와 같은 자릿수로 둔다.
     */
    private static final Duration MIN_NOT_ARRIVED_BACKOFF = Duration.ofMinutes(1);

    /**
     * 미도착 backoff 상한 — 대기가 길어져도 재시도 간격이 무한정 벌어지지 않게 한다.
     *
     * <p>backoff 는 "지금까지 기다린 만큼 더 기다린다"(경과 기반 지수 증가)라 상한이 없으면 상한(기본
     * 24h) 직전에 잡힌 예정 시각이 상한을 넘겨버려 <b>종결 판정 자체가 늦어진다</b>.
     */
    private static final Duration MAX_NOT_ARRIVED_BACKOFF = Duration.ofHours(1);

    /**
     * {@code SRC_TYPE} 허용값 (설계 §4-2) — 경계축은 "관제가 만들었나 / 저작도구가 만들었나"다.
     *
     * <p>관제 수신값은 신뢰 경계 밖이므로 화이트리스트 밖의 값은 <b>복사하지 않는다</b>(fail-closed).
     * 이 값은 화면 표시·파생 판별의 분기축이라 미지의 값이 그대로 들어오면 분기 결과가 미정의가 된다.
     *
     * <p>목록 정본은 {@link LsDataIngest#ALLOWED_SRC_TYPES} 다 — 내부 업로드 세션 생성(폼 입력 검증)도
     * 같은 목록을 써야 업로드분의 출처유형이 적재 시 조용히 null 이 되지 않는다.
     */
    static final Set<String> ALLOWED_SRC_TYPES = LsDataIngest.ALLOWED_SRC_TYPES;

    /** {@code LS_DATA_RAW.VDO_LEN_SEC} 는 {@code INT} — 인입 {@code NUMERIC(10)} 상한이 이를 넘는다. */
    private static final BigDecimal MAX_DURATION_SEC = BigDecimal.valueOf(Integer.MAX_VALUE);

    private final VideoRepository videoRepository;
    private final LsDataIngestRepository ingestRepository;
    private final VideoArtifactRootResolver rootResolver;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 이벤트유형 자동등록기 — 관제가 보낸 유형코드가 우리 마스터에 없으면 등록한다(V168).
     * 등록 실패는 흡수되며 적재를 막지 않는다(부가 기능).
     */
    private final EventTypeAutoRegistrar eventTypeAutoRegistrar;

    /**
     * 파일 미도착 대기 경과 상한(<b>{@code PRCS_DT} = 최초 미도착 관측 시각 기준</b>). 초과 시 종결(가역).
     *
     * <p>관제가 준 {@code RCPTN_DT} 를 기준으로 삼지 않는 이유는 클래스 javadoc 참조(설계 §6-0-1-a ㉠).
     */
    private final Duration notArrivedTimeout;

    /**
     * 관제 계약 갭 WARN 의 1회성 게이트(설계 §6-0-2).
     *
     * <p>결손은 <b>매 건</b> 발생하므로 건마다 WARN 을 내면 tick 당 상한(100)만큼 로그가 폭주해 정작
     * 실패 로그가 묻힌다. 프로세스 생애 1회만 WARN 하고 이후는 {@code DEBUG} 로 남긴다.
     * <p><b>static 이 아니라 인스턴스 필드</b>다 — 빈이 싱글턴이라 운영에서는 프로세스 1회로 동작하고,
     * 테스트에서는 인스턴스마다 초기화돼 실행 순서에 따라 단언이 흔들리지 않는다.
     */
    private final AtomicBoolean evntTypeGapWarned = new AtomicBoolean();
    private final AtomicBoolean shtDtGapWarned = new AtomicBoolean();

    public TrainingVideoIngestTx(
            VideoRepository videoRepository,
            LsDataIngestRepository ingestRepository,
            VideoArtifactRootResolver rootResolver,
            ApplicationEventPublisher eventPublisher,
            EventTypeAutoRegistrar eventTypeAutoRegistrar,
            @Value("${authoring.control.training-scan.not-arrived-timeout-hours:24}")
            long notArrivedTimeoutHours) {
        this.videoRepository = videoRepository;
        this.ingestRepository = ingestRepository;
        this.rootResolver = rootResolver;
        this.eventPublisher = eventPublisher;
        this.eventTypeAutoRegistrar = eventTypeAutoRegistrar;
        long hours = notArrivedTimeoutHours;
        if (hours < MIN_NOT_ARRIVED_TIMEOUT_HOURS) {
            log.warn("[TrainingIngest] not-arrived-timeout-hours={} 는 하한 미만 — {}시간으로 보정한다",
                    hours, MIN_NOT_ARRIVED_TIMEOUT_HOURS);
            hours = MIN_NOT_ARRIVED_TIMEOUT_HOURS;
        }
        this.notArrivedTimeout = Duration.ofHours(hours);
    }

    /** 파일 경로 판정 결과 — 적재 가능 / 아직 대기 / 영구 거부. */
    private enum PathVerdict {
        /** 허용 루트 하위의 실재 파일 — 적재 가능. */
        READY,
        /** 허용 루트 하위이나 파일이 아직 없다 — 다음 주기 재시도(실패 아님). */
        NOT_ARRIVED,
        /** 허용 루트 밖·경로 손상·심링크 이탈 — 재시도해도 통과 불가(CWE-22/59). */
        REJECTED
    }

    /**
     * 인입 1건을 독립(REQUIRES_NEW) 트랜잭션으로 적재한다.
     *
     * @param candidate 스캔이 넘긴 인입 행(외부 readOnly 세션 인스턴스 — 값 참조는 재조회분을 쓴다)
     * @return 신규 적재 성공 시 true, 그 외(클레임 실패/가드 skip/중복/파일 대기) false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "controlTransactionManager")
    public boolean ingestOne(LsDataIngest candidate) {
        Long rcptnSn = (candidate == null) ? null : candidate.getRcptnSn();
        if (rcptnSn == null) {
            log.warn("[TrainingIngest] skip ingest row without rcptnSn");
            return false;
        }
        // 규약 1 — 클레임이 최상단. 반환값 1 만이 "이 실행이 잡았다"의 근거다(엔티티 상태값은 stale).
        if (ingestRepository.claimForProcessing(rcptnSn) != 1) {
            log.debug("[TrainingIngest] claim lost — skip rcptnSn={}", rcptnSn);
            return false;
        }
        // 규약 2 — 상태 전이는 이 트랜잭션에서 다시 읽은 인스턴스에만 적용해야 영속된다.
        LsDataIngest ingest = ingestRepository.findById(rcptnSn).orElse(null);
        if (ingest == null) {
            // 인입 행은 영구 보존이라 도달 불가. 도달했다면 외부 삭제 신호이므로 크게 남긴다.
            log.error("[TrainingIngest] claimed row disappeared rcptnSn={}", rcptnSn);
            return false;
        }
        return ingestClaimed(ingest, rcptnSn);
    }

    /** 클레임 성공 이후의 적재 본문 — 모든 종결 전이는 재조회 인스턴스({@code ingest})에 적용한다. */
    private boolean ingestClaimed(LsDataIngest ingest, long rcptnSn) {
        // 식별자 가드 — 값 자체가 깨진 행은 재시도해도 같으므로 사유를 남기고 종결한다(좀비 방지).
        String missing = blankIdentifierColumn(ingest);
        if (missing != null) {
            log.warn("[TrainingIngest] skip ingest row with blank {} rcptnSn={}", missing, rcptnSn);
            ingest.markFailed(missing + " 누락 — 적재 불가");
            return false;
        }
        String vmsClipId = ingest.getVmsClipId();
        String rawFilePathNm = ingest.getRawFilePathNm();
        // 이중 멱등(1차) — 이미 적재된 클립이면 인입 행만 종결시킨다(무한 재조회 방지).
        Optional<LsDataRaw> already = videoRepository.findByVmsClipId(vmsClipId);
        if (already.isPresent()) {
            log.debug("[TrainingIngest] clip already ingested — skip rcptnSn={}", rcptnSn);
            ingest.markDone(already.get().getRawSn());
            return false;
        }
        PathVerdict verdict = verifyPath(rawFilePathNm);
        if (verdict == PathVerdict.REJECTED) {
            log.warn("[TrainingIngest] rejected raw file path rcptnSn={}", rcptnSn);
            ingest.markFailed("원본 영상 경로가 허용 저장 루트 밖이거나 유효하지 않음");
            return false;
        }
        if (verdict == PathVerdict.NOT_ARRIVED) {
            return handleNotArrived(ingest, rcptnSn);
        }
        return persistRaw(ingest, rcptnSn, vmsClipId, rawFilePathNm);
    }

    /**
     * 파일 미도착 처리 — <b>대기 상한 이내면 미처리 복귀(+backoff), 초과면 종결</b>
     * (설계 §6-0-1 ① / §6-0-1-a).
     *
     * <p>복귀만 하면 그 행이 FIFO 앞자리를 영구 점유해 뒤의 정상 인입이 굶는다(head-of-line blocking).
     * 반대로 상한을 짧게 잡으면 정상적인 파일 복사 지연을 조기 종결시킨다 — 그래서 상한은 설정값이고
     * 기본값이 넉넉하며, 종결은 재큐로 되돌릴 수 있다.
     *
     * <h3>경과 기준은 우리 시계({@code PRCS_DT})다</h3>
     * <p>앵커가 아직 없으면(= <b>이번이 최초 미도착 관측</b>) 경과는 0 이고, 복귀 UPDATE 가 그 시각을
     * 스탬프한다. 관제가 준 {@code RCPTN_DT} 는 여기서 <b>쓰지 않는다</b> — 과거 시각이 INSERT 되면
     * 파일이 도착하기도 전에 첫 픽업에서 종결되기 때문이다(설계 §6-0-1-a ㉠).
     *
     * <p><b>로그</b>: 정상 대기는 매 tick 반복되므로 {@code DEBUG}(운영 INFO 레벨에서 억제)로 남기고,
     * 상한 초과 종결은 행당 <b>정확히 1회</b> 발생하므로 {@code WARN} 으로 남긴다. 식별자는 인입 PK
     * (수치)만 남긴다 — 관제 자유텍스트·파일경로는 로그에 넣지 않는다(CWE-117 / CWE-359).
     *
     * @return 항상 false(신규 적재 아님)
     */
    private boolean handleNotArrived(LsDataIngest ingest, long rcptnSn) {
        LocalDateTime now = LocalDateTime.now();
        // 대기 예산 앵커 = 최초 미도착 관측 시각(우리 스탬프). 아직 없으면 이번이 최초라 경과 0 이다.
        LocalDateTime waitAnchor = ingest.getPrcsDt();
        Duration waited = (waitAnchor == null) ? Duration.ZERO : Duration.between(waitAnchor, now);
        if (waited.compareTo(notArrivedTimeout) > 0) {
            log.warn("[TrainingIngest] raw file not arrived within {}h — terminating rcptnSn={}",
                    notArrivedTimeout.toHours(), rcptnSn);
            // 사유 문구는 <운영자가 실제로 할 수 있는 일>만 적는다. 구 문구("경로 확인 후 재큐 필요")는
            // 버려진 업로드 행에도 그대로 붙어, 세션도 파일도 없는 행을 재큐하라고 권했다(재큐하면
            // 또 상한만큼 대기하다 재실패 — 무한 루프).
            ingest.markFailed("원본 영상 파일 미도착 대기 상한(" + notArrivedTimeout.toHours()
                    + "시간) 초과 — 종결(파일이 실제로 도착한 뒤에만 재큐가 의미 있다)");
            return false;
        }
        // R4 — 파일 대기는 실패가 아니다. PENDING 으로 되돌리되 <다음 시도를 뒤로 밀어> 이 행이
        //   폴링 앞자리를 잠식하지 않게 한다(§6-0-1-a ㉢).
        LocalDateTime nextRetryAt = now.plus(backoffFor(waited));
        log.debug("[TrainingIngest] raw file not arrived yet — deferring retry rcptnSn={} waitedSec={}",
                rcptnSn, waited.toSeconds());
        int reverted = ingestRepository.revertToPendingForRetry(rcptnSn, now, nextRetryAt);
        if (reverted != 1) {
            // 이 실행이 클레임한 행이 그 사이 다른 상태가 됐다 — 복귀가 안 됐으면 PROCESSING 좀비로
            // 남을 수 있으므로 반환값을 삼키지 않고 드러낸다.
            log.warn("[TrainingIngest] pending revert affected {} rows — row may stay PROCESSING rcptnSn={}",
                    reverted, rcptnSn);
        }
        return false;
    }

    /**
     * 미도착 backoff — <b>"지금까지 기다린 만큼 더 기다린다"</b>(하한·상한으로 clamp).
     *
     * <p>재시도 카운터를 쓰지 않는 이유는 {@code RTY_CNT} 가 <b>실패 이력 전용</b>이기 때문이다(대기
     * 카운터로 전용 금지 — 설계 구속). 경과 시간만으로 지수 증가(1분 → 2분 → 4분 …)를 얻을 수 있어
     * 별도 상태가 필요 없다.
     */
    private static Duration backoffFor(Duration waited) {
        if (waited.compareTo(MIN_NOT_ARRIVED_BACKOFF) < 0) {
            return MIN_NOT_ARRIVED_BACKOFF;
        }
        return waited.compareTo(MAX_NOT_ARRIVED_BACKOFF) > 0 ? MAX_NOT_ARRIVED_BACKOFF : waited;
    }

    /** 작업 테이블 적재 + 비식별 선두 트리거 + 인입 행 종결. */
    private boolean persistRaw(LsDataIngest ingest, long rcptnSn, String vmsClipId, String rawFilePathNm) {
        String evntTypeCd = resolveEvntTypeCd(ingest);
        warnControlContractGaps(ingest, rcptnSn, evntTypeCd);
        // ★ 이벤트유형 자동등록(V168) — 관제가 보낸 유형이 우리 마스터에 없으면 여기서 등록된다.
        //   <인입 소비 시점>에 두는 이유: 관제 인입도 내부 업로드(InternalUploadIngestWriter)도
        //   결국 이 경로 하나로 수렴하므로, 등록 지점을 호출부마다 배선하지 않아도 샐 곳이 없다.
        //   등록은 원자 upsert 이고 기존 행을 덮어쓰지 않는다(EventTypeAutoRegistrar 참조).
        eventTypeAutoRegistrar.register(evntTypeCd, ingest.getEvntNm(),
                ingest.getEvntClsfCd(), ingest.getEvntCtgryCd());
        try {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    // VMS_CCTV_ID 는 V185 이후 없을 수 있다(관제 확정 — 수동 업로드 등).
                    //   공백만 수신은 null 로 정규화한다 — 그대로 실으면 화면 표시명 폴백이
                    //   "값 있음"으로 오인해 빈칸을 그린다.
                    vmsClipId, trimToNull(ingest.getVmsCctvId()),
                    evntTypeCd, ingest.getLclgvCd(), DEFAULT_PRVC_TYPE,
                    rawFilePathNm, ingest.getShtDt(), toDurationSec(ingest.getVdoLenSec(), rcptnSn),
                    allowedSrcType(ingest.getSrcType(), rcptnSn));
            LsDataRaw saved = videoRepository.save(raw);
            // ★ 비식별 선두 파이프라인 트리거(AFTER_COMMIT → IngestDeidentifyBridge → AsyncDeidentifyRunner).
            //   이 발행이 빠지면 비식별이 아예 돌지 않아 원본 PII 가 그대로 남는다.
            eventPublisher.publishEvent(new VideoIngestedEvent(saved.getRawSn()));
            ingest.markDone(saved.getRawSn());
            log.info("[TrainingIngest] ingested rcptnSn={} rawSn={}", rcptnSn, saved.getRawSn());
            return true;
        } catch (DataIntegrityViolationException e) {
            // 이중 멱등(2차) — UK(VMS_CLIP_ID) 위반은 동시 race 의 중복 적재다.
            //   여기서 인입 행 상태를 더 건드리지 않는다: PostgreSQL 은 제약 위반 시 트랜잭션 전체를
            //   abort 하므로 이 트랜잭션의 클레임까지 함께 롤백되어 행이 PENDING 으로 돌아간다.
            //   다음 tick 이 다시 집으면 위 1차 멱등(findByVmsClipId)이 DONE 으로 종결시킨다.
            log.debug("[TrainingIngest] duplicate ingest race — skip rcptnSn={}", rcptnSn);
            return false;
        }
    }

    /**
     * 이벤트유형코드 확정 — <b>인입 {@code EVNT_TYPE_CD}(V166) 단독</b>.
     *
     * <h3>과도기 폴백이 사라진 경위 (관제 데이터 참조 전면 제거)</h3>
     * <p>이 값의 원래 출처는 관제 공유 테이블 {@code MNG_CLIP_EVNT_LST} 를 {@code EVNT_ID} 로 조인해
     * 해석하는 것이었다. 그 테이블을 지우려면 <b>먼저</b> 관제가 유형코드를 직접 실어 보낼 통로가
     * 있어야 하므로 인입에 {@code EVNT_TYPE_CD} 컬럼을 신설했고(V166), 관제 송신 반영 전까지는 조인
     * 해석을 폴백으로 유지했다. V167 이 그 테이블을 DROP 하면서 폴백은 <b>동작할 근거 자체가
     * 사라졌다</b> — 남겨두면 존재하지 않는 relation 을 조회해 적재 트랜잭션이 통째로 실패한다.
     *
     * <h3>결손은 적재 실패가 아니다 (2026-08-04 사용자 확정 — 유지)</h3>
     * <p>관제가 유형코드를 안 보내면 null 로 적재하고 <b>적재는 성공</b>시킨다. 적재를 실패시키면
     * 영상이 아예 들어오지 않아 되돌리기가 더 어렵다. 결손은 마킹 단계에서 기존 가드
     * ({@code MarkingGuards#requirePreconditions} — 400)가 그대로 막고,
     * {@link #warnControlContractGaps} 가 관측 가능하게 만든다.
     *
     * <p><b>판정은 이 메서드 하나에만 둔다</b> — 같은 판정을 호출부마다 복제하면 한쪽만 갱신돼
     * 어긋난다(이 저장소의 반복 사고 패턴).
     *
     * @return 확정된 유형코드, 관제 미송신·공백이면 {@code null}
     */
    private String resolveEvntTypeCd(LsDataIngest ingest) {
        return trimToNull(ingest.getEvntTypeCd());
    }

    /**
     * <b>관제가 안 주는 값을 조용히 비우지 않는다</b>(설계 §6-0-2) — 적재 시점에 결손을 관측 가능하게 한다.
     *
     * <ul>
     *   <li>{@code EVNT_TYPE_CD} — 관제가 인입 평면값으로 <b>안 보내면</b> null 이다(V167 이후 조인
     *       해석 폴백은 없다). 값이 오면 결손이 아니므로 경고하지 않는다(경고 인플레이션 방지).
     *       결손 시 영향: 마킹 진입 차단(400) · 관제 완료통지 계약 필드 ·
     *       작업/검수목록 이벤트유형 필터 · 통계 버킷 · export 메타.</li>
     *   <li>{@code SHT_DT} — 관제가 <b>안 채우면</b> null 이다. 구 {@code CRT_DT} 폴백은
     *       <b>복원하지 않는다</b> — 촬영일시는 촬영환경(시간대·계절) 파생의 근거라 대용값을 넣으면
     *       <b>틀린 값으로 확정</b>되어 null 보다 나쁘다.</li>
     * </ul>
     *
     * <p>결손이 <b>조용한 것</b>이 결함이다. 매 건 WARN 은 로그 폭주라 프로세스 1회만 WARN 하고 이후는
     * {@code DEBUG} 로 남긴다.
     */
    private void warnControlContractGaps(LsDataIngest ingest, long rcptnSn, String evntTypeCd) {
        if (evntTypeCd == null) {
            if (evntTypeGapWarned.compareAndSet(false, true)) {
                log.warn("[TrainingIngest] EVNT_TYPE_CD 미수신 — 관제가 인입에 이벤트유형코드를"
                        + " 보내지 않아 null 로 적재한다(대용값을 넣지 않는다). 이 영상은 마킹 단계에서"
                        + " 차단된다. 관제에 평면 송신 계약 요청 필요."
                        + " 이후 동일 사례는 DEBUG 로만 남긴다. rcptnSn={}", rcptnSn);
            } else {
                log.debug("[TrainingIngest] evntTypeCd absent — ingested as null rcptnSn={}", rcptnSn);
            }
        }
        if (ingest.getShtDt() != null) {
            return;
        }
        if (shtDtGapWarned.compareAndSet(false, true)) {
            log.warn("[TrainingIngest] SHT_DT 미수신 — 촬영일시 없이 적재한다(대용값으로 채우지 않는다)."
                    + " 관제에 NOT NULL 계약 요청 필요. 이후 동일 사례는 DEBUG 로만 남긴다. rcptnSn={}",
                    rcptnSn);
        } else {
            log.debug("[TrainingIngest] shtDt missing — ingested as null rcptnSn={}", rcptnSn);
        }
    }

    /** 공백만인 수신 문자열을 null 로 정규화한다(빈 문자열을 "값 있음"으로 오인하지 않게). */
    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * 적재에 필수인 식별자 2종 중 <b>비어 있는 첫 컬럼명</b>(없으면 null).
     *
     * <p>{@code VMS_CLIP_ID}(멱등키 — 비면 {@code findByVmsClipId} 가 오작동) →
     * {@code RAW_FILE_PATH_NM}(비식별이 열 파일이 없다).
     *
     * <h3>★ {@code VMS_CCTV_ID} 는 더 이상 여기 없다 (V185 · @design ERD-012)</h3>
     * <p>구 가드의 근거는 "{@code LS_DATA_RAW.VMS_CCTV_ID} 가 NOT NULL 이라 사전 skip 이 없으면 제약
     * 위반이 중복 race 로 오인된다" 였다. 관제서버팀 회신(2026-08-12)으로 <b>CCTV 식별자가 없는
     * 영상(수동 업로드 등)이 존재</b>함이 확정되어 두 테이블의 NOT NULL 을 해제했고 그 근거가 소멸했다.
     * <p>가드를 남겨두면 <b>인입 행은 받되 원시영상 적재에서 걸러져</b> 관제 요구가 실질 미충족이 된다
     * — 그 영상이 저작도구 어디에도 나타나지 않는다.
     * <p>남은 둘은 <b>그대로</b>다. 둘 다 값이 없으면 재시도해도 같은 결과인 영구 사유다.
     *
     * <p>반환값은 <b>컬럼명 상수</b>라 로그·{@code ERR_MSG} 에 그대로 써도 안전하다(수신값 미포함).
     */
    private static String blankIdentifierColumn(LsDataIngest ingest) {
        if (!StringUtils.hasText(ingest.getVmsClipId())) {
            return "VMS_CLIP_ID";
        }
        if (!StringUtils.hasText(ingest.getRawFilePathNm())) {
            return "RAW_FILE_PATH_NM";
        }
        return null;
    }

    /**
     * 관제 수신 경로 판정 — 허용 루트(고정 allowlist) 하위인지 + 파일이 도착했는지.
     *
     * <p>관제 수신값은 <b>신뢰 경계 밖</b>이다. 이 값은 단순 읽기 힌트가 아니라 이후 산출물 쓰기
     * base({@code dirname(값)/{rawSn}/})로 승격되므로, 적재 <b>전에</b> 걸러야 한다(CWE-22).
     * 검증은 {@link VideoArtifactRootResolver#verifyIngestablePath}(lexical 정규화 + 상위 디렉터리
     * 실경로 재검증)에 위임한다. 그 판정이 커버하지 못하는 것은 <b>최종 컴포넌트</b>다 — 검증된
     * 디렉터리 안의 파일명이 바깥을 가리키는 심링크일 수 있다(CWE-59). 그래서 <b>파일의 최종 실경로가
     * 허용 루트 하위인지</b>를 추가로 확인한다.
     *
     * <h3>판정 기준은 "같은 디렉터리"가 아니라 "허용 루트 하위"다 (설계 §6-0-1 ③)</h3>
     * <p>구 구현은 {@code realParent.equals(real.getParent())} 로 <b>심링크 타깃이 같은 디렉터리</b>일 것을
     * 요구했다. 그러면 {@code /nas/videos/clip.mp4 → /nas/videos/2026/07/clip.mp4} 같은 <b>허용 루트 안의
     * 평범한 NAS 레이아웃</b>까지 거부하고 영구 종결시킨다. 보안 목적(허용 루트 <b>밖</b>을 가리키는
     * 심링크 차단)은 루트 기준 판정으로 그대로 달성되고 오탐만 사라진다.
     *
     * <p>실경로를 {@code verifyIngestablePath} 에 다시 넣지는 않는다 — 그 판정에는 <b>lexical</b> 단계가
     * 있어 allowlist 원소 자체가 심링크 경로(macOS 의 {@code /var} → {@code /private/var} 등)면
     * <b>정상 경로가 거기서 탈락</b>한다. 여기서는 <b>실경로 대 실경로</b>로만 비교한다.
     */
    private PathVerdict verifyPath(String rawFilePathNm) {
        try {
            rootResolver.verifyIngestablePath(rawFilePathNm);
        } catch (CustomException e) {
            return PathVerdict.REJECTED;
        }
        Path resolved;
        try {
            resolved = Paths.get(rawFilePathNm).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return PathVerdict.REJECTED;
        }
        if (!Files.exists(resolved)) {
            return PathVerdict.NOT_ARRIVED;
        }
        Path real;
        try {
            real = resolved.toRealPath();
        } catch (IOException e) {
            // 권한/깨진 링크 — 적재하지 않고 다음 주기에 다시 본다(영구 종결로 굳히지 않는다).
            // 스스로 낫지 않는 상태여도 대기 상한이 종결시키므로 큐가 막히지 않는다.
            return PathVerdict.NOT_ARRIVED;
        }
        if (!Files.isRegularFile(real)) {
            return PathVerdict.NOT_ARRIVED;
        }
        if (!isUnderAllowedRoot(real)) {
            // 허용 루트 밖(다른 트리)을 가리키는 심링크 — 실제로 열릴 파일이 검증 대상과 다르다.
            return PathVerdict.REJECTED;
        }
        if (isEmptyFile(real)) {
            return PathVerdict.NOT_ARRIVED;
        }
        return PathVerdict.READY;
    }

    /**
     * <b>완결성 게이트</b> — 크기 0 파일은 "아직 도착하지 않은 것"으로 본다 (DEV_FIX H1).
     *
     * <h3>왜 존재(exists)만으로는 부족한가</h3>
     * <p>이 판정의 구 구현은 파일이 <b>'없음' 또는 '완성'</b> 두 상태만 갖는다고 가정했다. 실제로는
     * <b>세 번째 상태</b>가 있다:
     * <ul>
     *   <li>관제가 NAS 로 <b>복사 중</b>인 파일 — 대용량일수록 창이 길다.</li>
     *   <li>내부 업로드가 남긴 <b>0바이트 잔여물</b> — 이동 실패 후 회수까지 실패한 경우.</li>
     * </ul>
     * <p>그대로 적재하면 {@code LS_DATA_RAW} 가 생기고 비식별 선두 파이프라인이 <b>빈 파일로 기동</b>
     * 하는데, 인입 행은 {@code DONE} 이라 재큐 통로({@code FAILED} 전용)조차 없다 — 되돌릴 수 없는
     * 오적재다.
     *
     * <h3>왜 기준이 "0바이트" 하나인가</h3>
     * <p>임의의 최소 크기(예: 1KB)나 "크기가 안정될 때까지 2회 관측" 같은 규칙을 두면 <b>정상 영상을
     * 굶기거나</b> 판정이 시계·주기에 의존하게 된다. 크기 0 은 <b>어떤 영상 컨테이너도 될 수 없는</b>
     * 값이라 오탐이 구조적으로 없고, 판정도 1회 관측으로 끝난다. 복사 중 파일이 0바이트를 지나
     * 부분 크기가 되는 창은 이 게이트로 막히지 않지만, 그건 매직바이트·ffprobe 를 통과한 뒤 원자
     * rename 으로만 최종 경로에 놓는 <b>쓰기 측 규약</b>(내부 업로드)과 관제 측 복사 규약의 몫이다.
     *
     * <p>판정은 <b>실패가 아니라 대기</b>({@code NOT_ARRIVED})다 — 복사가 끝나면 다음 주기에 적재되고,
     * 영영 끝나지 않으면 미도착 대기 상한이 종결시킨다.
     */
    private static boolean isEmptyFile(Path real) {
        try {
            return Files.size(real) <= 0L;
        } catch (IOException e) {
            // 크기를 못 읽는 상태(권한·마운트 단절)도 적재 대상이 아니다 — 다음 주기에 다시 본다.
            return true;
        }
    }

    /**
     * 최종 실경로가 고정 allowlist({@code authoring.storage.raw-mount-roots}) 하위인가 (CWE-59).
     *
     * <p>루트도 심링크일 수 있으므로 <b>양쪽 모두 실경로로 접어</b> 비교한다. 루트가 아직 없으면
     * (로컬 형상에서 저장소 디렉터리 미생성 등) 해석 실패는 선언값 그대로 비교한다 — 그 경우 대상이
     * 그 아래 있을 수도 없으므로 허용이 넓어지지 않는다.
     */
    private boolean isUnderAllowedRoot(Path realPath) {
        for (Path root : rootResolver.allowedRoots()) {
            if (realPath.startsWith(realOrSelf(root))) {
                return true;
            }
        }
        return false;
    }

    private static Path realOrSelf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    /**
     * {@code SRC_TYPE} allowlist 검증 — 미매칭이면 복사하지 않는다(fail-closed).
     *
     * <p>값 원문은 {@link LogSanitizer} 로 정제해 로그에 남긴다(CWE-117 — 관제 자유텍스트).
     */
    private static String allowedSrcType(String srcType, long rcptnSn) {
        if (!StringUtils.hasText(srcType)) {
            return null;
        }
        if (ALLOWED_SRC_TYPES.contains(srcType)) {
            return srcType;
        }
        log.warn("[TrainingIngest] unknown srcType — not copied rcptnSn={} value={}",
                rcptnSn, LogSanitizer.sanitize(srcType, 40));
        return null;
    }

    /**
     * {@code LS_DATA_INGEST.VDO_LEN_SEC}(<b>초</b>, {@code NUMERIC(10)}) →
     * {@code LS_DATA_RAW.VDO_LEN_SEC}({@code INT}, 초).
     *
     * <p><b>단위 변환을 하지 않는다</b> — 구 경로(관제 공유 클립 마스터)의 값이 ms 라 ÷1000 하던
     * 것을 그대로 가져오면 600초 영상이 0.6→null 이 되어 <b>길이가 사실상 전부 사라진다</b>.
     *
     * <p>1초 미만(0·음수 포함)은 {@code 0} 을 영속하지 않고 null 로 둔다 — "길이 0" 오값으로 굳으면
     * 통계·표시가 오염되고, 적재 직후 ffprobe back-fill({@code VideoMetaService})이 실제 길이로
     * 채운다. {@code INT} 범위를 넘는 값도 접히지 않게 null 로 두고 back-fill 에 위임한다.
     */
    private static Integer toDurationSec(BigDecimal vdoLenSec, long rcptnSn) {
        if (vdoLenSec == null) {
            return null;
        }
        BigDecimal seconds = vdoLenSec.setScale(0, RoundingMode.HALF_UP);
        if (seconds.compareTo(BigDecimal.ONE) < 0) {
            return null;
        }
        if (seconds.compareTo(MAX_DURATION_SEC) > 0) {
            log.warn("[TrainingIngest] vdoLenSec exceeds INT range — left null rcptnSn={}", rcptnSn);
            return null;
        }
        return seconds.intValue();
    }
}
