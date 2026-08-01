package kr.co.cudo.authoring.augment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.VisibleTextNormalizer;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V1.5 SFR-07 — 외부 증강 시스템 요청 서비스 (콜백 충실 플로우 Phase 1).
 *
 * <p>저작도구는 검수 완료된 영상(LsRawDataStatus.dataSttsCd='APPROVED')만 증강 요청 가능.
 * 요청 1건에 대해 다음 선행 상태를 만들어 콜백이 성립하게 한다:
 *
 * <ol>
 *   <li>영상의 대표 프레임(MIN SRC_SN) 조회</li>
 *   <li>idempotencyKey(^[A-Za-z0-9_-]+$, ≤64) 를 먼저 발급
 *       (externalJobId 는 발급하지 않는다 — 외부가 202 응답으로 준다, Phase 7-A1)</li>
 *   <li>{@link LsDataAug#createRequested} 로 키를 실은 PENDING 행을 <b>단일 save</b> 적재 → originAugSn</li>
 *   <li>{@link AugmentRequestedItemEvent} 발행 (외부 위탁은 커밋 이후로 위임)</li>
 * </ol>
 *
 * <h3>단건 계약이 정본이다 (E-ISSUE-08)</h3>
 * <p>한 요청 = <b>영상 1건 × 종류 1개</b>. API 계약은 {@code AugmentRequestRequest} 의
 * {@code @NotEmpty + @Size(max=1)} 이고, 서비스도 같은 규칙을 fail-closed 로 재확인한다
 * ({@code requireSingleSelection}). 과거에는 여기에 distinct 정규화 + (영상 × 종류) 이중 루프가
 * 남아 있었지만 DTO 가 길이 1 만 허용하므로 <b>실행될 수 없는 사문 코드</b>였고, 그 전제 위에 쓰인
 * 테스트(중복 입력 distinct 등)도 계약을 잘못 고정하고 있었다. 다건 재허용이 필요해지면 DTO 계약
 * 변경 → 서비스 → 테스트 순으로 <b>의도적으로</b> 열어야 한다.
 *
 * <h3>생성 0건은 성공이 아니다 (E-ISSUE-09)</h3>
 * <p>프레임 미추출 영상은 위탁 입력({@code input_files})을 만들 수 없다. 구 구현은 그런 영상을
 * 조용히 스킵하고 요청 개수를 그대로 담아 200 을 돌려줘, REVIEWER 는 아무것도 접수되지 않았는데
 * 접수된 줄 알았다. 지금은 <b>412(PRECONDITION_FAILED)</b> 로 종결하고 어떤 영상이 막혔는지
 * ({@code skippedVideoIds}) 알린다. 응답은 요청 echo 가 아니라 실제 생성 수
 * ({@code createdCount})를 담는다.
 *
 * <h3>같은 (영상 × 종류) 재요청은 <b>몇 번이든 허용</b>한다 (2026-07-31 정책 전환)</h3>
 * <p>구 구현은 활성(PENDING·ACCEPTED) 중복 요청을 409 로 막고 DB 부분 유니크
 * ({@code UK_LS_DATA_AUG_ACTVTN}, V143)로 최종 방어했다("요청 1회 = 파생영상 1건"). <b>사용자 확정으로
 * 폐기</b>됐다 — 증강 결과 이미지는 요청마다 다르게 생성되므로, 원하는 결과가 나오지 않으면 같은
 * 영상·같은 종류로 다시 요청하는 것이 정상 운영 동선이기 때문이다. 사전 조회 가드·안내 문구·제약 위반
 * 409 분기·인덱스(V153 DROP)를 모두 제거했다. 오조작(연타) 방어는 <b>FE 단독 책임</b>이다.
 *
 * <h3>요청마다 <b>생성 조건(prompt)</b>을 받는다</h3>
 * <p>REVIEWER 가 5필드(time/season/weather/terrain/severity)를 입력하면 그대로 외부로 나가고
 * ({@code AugmentPrompts}) 동시에 {@code LS_DATA_AUG.PROMPT_CN} 에 원문이 남는다. 반복 요청이 허용되는
 * 이상 "이 파생본은 어떤 조건으로 만든 것인가" 를 남기지 않으면 결과물을 구분할 수 없다.
 * <b>증강 유형({@code AUG_TYPE_CD})은 prompt 에서 파생하지 않는다</b> — 자유 문자열이 유형으로 흘러가면
 * 산출물 경로 순회(CWE-22)와 {@code RESL_} 네임스페이스 침범이 열린다({@code AugmentPrompts} 주석).
 *
 * <p><b>고아 위탁 방지 (DEV_FIX HIGH #1)</b>: 외부 위탁({@code AugmentJobSubmitService.submit})은
 * 요청 트랜잭션 안에서 하지 않고, {@code AugmentRequestBridge} 가
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 로 수신해 <b>커밋 확정 후</b>에만 수행한다.
 * 요청 트랜잭션이 롤백되면 aug 행도 위탁도 발생하지 않는다.
 *
 * <p>RBAC: REVIEWER 만 호출 가능 (Service 이중 검증 + Controller @PreAuthorize).
 * <p>트랜잭션: 클래스 기본 readOnly, {@link #request} 만 write override (aug 행 INSERT).
 * <p>실패 격리: 적재 실패를 <b>삼키지 않는다</b> — 사유를 로그로 남기고 호출자에게 실패로 회신한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AugmentRequestService {

    /** 멱등 키 형식 — 콜백 회신 시 동일 키로 매칭되므로 발급 시점에 강제 (CWE-20). */
    private static final java.util.regex.Pattern IDEMPOTENCY_KEY_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final int IDEMPOTENCY_KEY_MAX = 64;
    /**
     * 콜백 경로 — 단일 진실원
     * ({@link kr.co.cudo.authoring.common.security.webhook.WebhookProtectedPaths#PATH_GENAI_CALLBACK}) 참조.
     *
     * <p>Phase 7-A1: 「생성형 AI API 연동명세서 v1.1」 웹훅 경로로 교체했다. 수신 컨트롤러는 A2 에서
     * 같은 상수를 참조해 추가한다(구 {@code /v1/aug/callback} 는 A2 에서 정리).
     */
    public static final String CALLBACK_PATH = AugmentCallbackUrlResolver.CALLBACK_PATH;

    private final LsRawDataStatusRepository statusRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataAugRepository augRepository;
    /** 파생 영상 판정용 — {@code ORGNL_RAW_SN} 하나만 본다(조상 체인 순회 금지, 2026-07-29 철회 정책). */
    private final VideoRepository videoRepository;
    private final ApplicationEventPublisher eventPublisher;
    /** 비식별 누락 신고 구간 판정 — 단일 원천(자체 재구현 금지). */
    private final DeidentReportGate deidentReportGate;
    /** 콜백 URL 조립 — 요청 경로와 재개 경로가 같은 값을 만들게 하는 단일 원천. */
    private final AugmentCallbackUrlResolver callbackUrlResolver;
    /** prompt 보관용 JSON 직렬화 — 외부로 나가는 dict 를 그대로 문자열화한다(전송본↔저장본 동일 출처). */
    private final ObjectMapper objectMapper;

    /**
     * placeholder jobId 시퀀스 — 외부 SFR-07 연동 전까지 응답 jobId 발급에 사용.
     * 동시성 안전을 위해 AtomicLong 사용. base 는 인스턴스 시작 시각(ms) 으로 충돌 회피.
     */
    private final AtomicLong jobIdSeq = new AtomicLong(System.currentTimeMillis());

    /**
     * 외부 SFR-07 증강 시스템에 검수 완료 영상 + 증강 유형을 요청한다.
     *
     * @param request 영상 1건 + 종류 1개 (DTO 단계 형식 검증 통과)
     * @param actor   호출자 토큰 (REVIEWER 만 허용)
     * @return jobId / 요청 시각 / 요청 수 / <b>실제 생성 수</b>
     * @throws CustomException FORBIDDEN(WORKER 등), INVALID_INPUT(단건 계약·프롬프트 형식 위반),
     *                         NOT_REVIEWED(미검수), PRECONDITION_FAILED(신고 구간·프레임 미추출),
     *                         CONFLICT(제약 위반 — 멱등 키 충돌·정합 충돌),
     *                         INTERNAL_ERROR(적재 실패 — 생성 0건)
     */
    @Transactional("controlTransactionManager")
    public AugmentRequestResponse request(AugmentRequestRequest request, TokenClaims actor) {
        requireReviewer(actor);

        // 1) 단건 계약 강제 (E-ISSUE-08) — DTO @Size(max=1) 과 같은 규칙을 서비스에서도 fail-closed 로
        //    확인한다. 초과분을 조용히 잘라 첫 건만 처리하면 요청자는 나머지도 접수된 줄 안다.
        Long rawSn = requireSingleSelection(request.videoIds(),
                "영상은 한 번에 1건만 증강 요청할 수 있습니다.");
        String augType = requireSingleSelection(request.types(),
                "증강 종류는 한 번에 1개만 선택할 수 있습니다.").name();
        List<Long> videoIds = List.of(rawSn);

        // 1-1) 프롬프트 정규화 + 직렬화 — 외부 전송본과 DB 보관본이 <같은 dict> 에서 나오게 한다.
        //      순수 입력 검증이므로 <DB 조회보다 먼저> 한다: 형식이 틀린 요청 하나가 검수상태·신고구간·
        //      프레임 조회 3회를 유발하면 인증 사용자가 반복 호출로 DB 부하를 증폭시킬 수 있다(CWE-770).
        PromptPayload prompt = buildPrompt(request.prompt());

        // 1-2) 파생 영상 차단 — <b>다른 어떤 사유보다 먼저</b> 판정한다.
        //      해상도 변경 경로({@code VideoResolutionService.loadAndValidate})는 이미 같은 가드를
        //      APPROVED 검증 <앞>에 두고 있다. 두 경로의 순서가 어긋나면 파생 영상 증강 요청 시
        //      "미검수"/"신고 구간" 같은 <엉뚱한 사유>가 먼저 뜨고(파생본은 통상 미검수 상태다)
        //      요청자는 진짜 사유에 영원히 도달하지 못한다.
        //      단 인가 검사(requireReviewer) <뒤>여야 한다 — 앞서면 응답 코드가 "그 영상이 존재하는가/
        //      파생인가" 를 알려주는 오라클이 된다(CWE-209).
        requireNotDerivative(rawSn);

        // 2) 검수 완료(APPROVED) 검증 — 미검수면 거부
        List<Long> blocked = findBlockedVideoIds(videoIds);
        if (!blocked.isEmpty()) {
            log.info("[Augment] request blocked — not reviewed actor={} blockedCount={}",
                    sanitize(actor.sub()), blocked.size());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("blockedVideoIds", blocked);
            throw new CustomException(
                    ErrorCode.NOT_REVIEWED,
                    ErrorCode.NOT_REVIEWED.defaultMessage(),
                    details);
        }

        // 2-1) 비식별 누락 신고 구간 차단 (DEV_FIX HIGH-2) — 신고된 영상은 아예 접수하지 않는다.
        //      실제 <b>전송</b> 차단은 AugmentJobSubmitService.submit(전송 진입점 단일 fail-closed)이
        //      담당하며, 여기서의 조기 거부는 ①요청자에게 즉시 사유를 알리고 ②고착될 PENDING 행을
        //      애초에 만들지 않기 위한 것이다(보류이므로 해소 후 재요청하면 정상 진행된다).
        List<Long> underReport = videoIds.stream()
                .filter(deidentReportGate::isUnderDeidentReport)
                .toList();
        if (!underReport.isEmpty()) {
            log.warn("[Augment] request blocked — deident report open actor={} blockedCount={}",
                    sanitize(actor.sub()), underReport.size());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("blockedVideoIds", underReport);
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "비식별 재처리 대기 중인 영상은 증강을 요청할 수 없습니다.", details);
        }

        // 3) 대표 프레임(MIN SRC_SN) 조회 — 없으면 위탁 입력이 성립하지 않는다(E-ISSUE-09).
        Long representativeSrcSn = findFirstSrcSn(rawSn);
        if (representativeSrcSn == null) {
            // 구 구현은 이 영상을 조용히 스킵하고 200 을 돌려줘, 생성 0건인데 접수된 것처럼 보였다.
            log.warn("[Augment] request blocked — no frame extracted actor={} rawSn={}",
                    sanitize(actor.sub()), rawSn);
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "프레임이 추출되지 않은 영상은 증강을 요청할 수 없습니다.",
                    skippedDetails(rawSn));
        }

        // 4) PENDING 적재 + 멱등 키 발급 + 콜백 컨텍스트 전달.
        //    실패는 삼키지 않는다 — 사유를 남기고(로그) 호출자에게 실패로 회신한다(E-ISSUE-09).
        String regUserNo = actor.sub();
        String callbackUrl = callbackUrlResolver.resolve();
        boolean created = createOneAugmentRequest(
                rawSn, representativeSrcSn, augType, regUserNo, callbackUrl, prompt);
        if (!created) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "증강 요청을 생성하지 못했습니다.", skippedDetails(rawSn));
        }

        long jobId = jobIdSeq.incrementAndGet();
        LocalDateTime requestedAt = LocalDateTime.now();
        log.info("[Augment] requested jobId={} actor={} rawSn={} augType={} createdAugCount=1",
                jobId, sanitize(regUserNo), rawSn, sanitize(augType));
        return new AugmentRequestResponse(jobId, requestedAt, 1, 1, 1);
    }

    /**
     * 사용자 입력 5필드 → 외부 전송 dict + DB 보관 JSON.
     *
     * <h3>정규화는 {@code VisibleTextNormalizer} 단일 원천을 쓴다</h3>
     * <p>제어문자(개행·탭·NUL)뿐 아니라 <b>보이지 않는 문자</b>(NBSP·ZWSP·BOM·WJ·RLO·U+2028/2029)까지
     * 걷어내고 앞뒤 공백을 다듬는다. 개행이 남으면 ①이 값이 로그에 닿는 순간 로그 위조(CWE-117)가 되고
     * ②{@code U+0000} 은 PgJDBC 가 거부해 적재가 500 이 된다. 그리고 <b>보이지 않는 문자만 채운 값</b>은
     * {@code @NotBlank}({@code trim()} 기준)를 그대로 통과하므로, 여기서 걸러내지 않으면 "빈 조건" 이
     * 벤더까지 나가 결과가 비결정적이 된다(이 DTO 가 막겠다고 선언한 바로 그 상태).
     *
     * <p>공용 {@code ControlCharNormalizer} 를 넓히지 않은 이유는 그 규칙이 <b>SQL 표현식과 등가</b>여야
     * 하는 제약을 지고 있기 때문이다({@code VisibleTextNormalizer} 주석 참조) — 한쪽만 넓히면 목록 필터
     * 왕복이 조용히 깨진다.
     *
     * <h3>DTO 검증을 서비스에서 다시 확인하는 이유 (fail-closed)</h3>
     * <p>{@code @NotBlank}/{@code @Size} 는 <b>컨트롤러 진입</b>에만 적용된다. 서비스를 직접 부르는 경로
     * (내부 호출·테스트)가 상한을 우회해 {@code PROMPT_CN}(VARCHAR(4000)) 적재 오류나 무제한 외부 중계로
     * 이어지지 않도록 같은 규칙을 여기서도 확인한다({@code requireSingleSelection} 과 동일한 태도).
     * 정규화로 제어문자만 남는 값이 사라지면 "공백만 입력" 과 동치이므로 함께 거부한다.
     *
     * <p>오류 메시지에는 <b>필드 이름만</b> 싣고 입력값은 넣지 않는다 — 입력값을 되돌려주면 그 자체가
     * 반사형 노출 경로가 되고, 사용자가 PII 를 적었을 경우 응답·로그로 번진다(CWE-359).
     */
    private PromptPayload buildPrompt(AugmentRequestRequest.PromptFields fields) {
        if (fields == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "증강 생성 조건(prompt)은 필수입니다.");
        }
        Map<String, Object> prompt = AugmentPrompts.of(
                requirePromptField(fields.time(), AugmentPrompts.KEY_TIME),
                requirePromptField(fields.season(), AugmentPrompts.KEY_SEASON),
                requirePromptField(fields.weather(), AugmentPrompts.KEY_WEATHER),
                requirePromptField(fields.terrain(), AugmentPrompts.KEY_TERRAIN),
                requirePromptField(fields.severity(), AugmentPrompts.KEY_SEVERITY));
        return new PromptPayload(prompt, serializePrompt(prompt));
    }

    /** 프롬프트 1필드 정규화 + 필수/길이 재확인. 실패 시 필드명만 알린다. */
    private static String requirePromptField(String raw, String key) {
        String normalized = VisibleTextNormalizer.normalizeOrNull(raw);
        if (normalized == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "증강 생성 조건 항목은 비워둘 수 없습니다: " + key);
        }
        if (normalized.length() > AugmentRequestRequest.PromptFields.MAX_FIELD_LENGTH) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "증강 생성 조건 항목이 너무 깁니다(최대 "
                            + AugmentRequestRequest.PromptFields.MAX_FIELD_LENGTH + "자): " + key);
        }
        return normalized;
    }

    /**
     * 보관용 JSON 직렬화 — 외부로 나가는 dict 와 <b>같은 객체</b>에서 만든다(전송본↔저장본 불일치 차단).
     * 실패 원문은 응답으로 내보내지 않는다(CWE-209).
     */
    private String serializePrompt(Map<String, Object> prompt) {
        try {
            return objectMapper.writeValueAsString(prompt);
        } catch (JsonProcessingException e) {
            log.error("[Augment] prompt 직렬화 실패 keys={}", prompt.keySet());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "증강 요청을 생성하지 못했습니다.");
        }
    }

    /**
     * 프롬프트의 두 표현 — 외부 전송용 dict 와 DB 보관용 JSON 문자열.
     *
     * <p>한 쌍으로 묶어 다니는 이유는 <b>둘이 갈라지지 않게</b> 하기 위함이다. 각각을 따로 만들어
     * 넘기면 위탁된 조건과 적재된 조건이 달라져 사후 역추적이 거짓이 된다.
     *
     * @param fields 외부 전송 dict(불변)
     * @param json   위 dict 를 직렬화한 원문 — {@code LS_DATA_AUG.PROMPT_CN} 적재값
     */
    private record PromptPayload(Map<String, Object> fields, String json) {
    }

    /**
     * 단건 계약(E-ISSUE-08) 확인 — 정확히 1건만 허용한다.
     *
     * <p>DTO 의 {@code @NotEmpty + @Size(max=1)} 이 API 계약의 정본이지만, 그 검증은 컨트롤러
     * 진입에만 적용된다. 서비스가 초과분을 조용히 자르면 "요청은 2건인데 1건만 접수" 라는 은폐된
     * 부분 처리가 생기므로 여기서도 fail-closed 로 거부한다(CWE-20).
     */
    private static <T> T requireSingleSelection(List<T> values, String message) {
        if (values == null || values.size() != 1 || values.get(0) == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, message);
        }
        return values.get(0);
    }

    /**
     * 파생 영상(증강·해상도 산출물)에서의 증강 요청을 거부한다 — 해상도 변경 경로와 동일 정책·동일
     * 에러코드 계열({@link ErrorCode#INVALID_INPUT}, 400).
     *
     * <p><b>신규 정책이 아니라 드리프트 정정</b>이다: 해상도 변경은 {@code ORGNL_RAW_SN IS NOT NULL} 인
     * 영상을 이미 거부하는데(중첩 파생 금지) 증강 요청만 그 가드가 없어, 파생본에서 또 파생을 만들 수
     * 있었다. 새 에러코드를 만들지 않는다.
     *
     * <p><b>원본으로 유도하지 않고 부모 rawSn 도 내려주지 않는다</b> — 파생에 배정된 WORKER 는 원본
     * 접근 권한이 없어({@code LabelAccessGuard} 403) 따라갈 수 없고, 부모 식별자를 실어 주면 접근
     * 권한이 없는 자원의 존재를 알려 주는 셈이 된다(CWE-209/639).
     *
     * <p>이미 존재하는 손자 파생(깊이 2+)은 정리하지 않는다 — 신규 생성만 막는다.
     *
     * <p><b>영상이 없으면 여기서 판단하지 않는다 (응답 계약 보존, DEV_FIX LOW)</b>: 이 가드의 책임은
     * "파생인가" 하나이고 <b>존재 여부는 다음 단계(APPROVED 검증)의 축</b>이다. 중간 구현은 조회 실패를
     * {@link ErrorCode#NOT_FOUND}(404)로 던져, 존재하지 않는 {@code rawSn} 의 응답이 종전
     * {@link ErrorCode#NOT_REVIEWED}(400)에서 <b>조용히 404 로 바뀌었다</b> — 이 가드가 의도한 정책
     * 변경이 아니라 부수 효과이므로 되돌린다. 영상이 없으면 파생도 아니므로 그대로 통과시키고,
     * 곧바로 {@code findBlockedVideoIds} 가 "미검수" 로 종전과 동일하게 거부한다.
     */
    private void requireNotDerivative(Long rawSn) {
        LsDataRaw video = videoRepository.findById(rawSn).orElse(null);
        if (video == null) {
            return;
        }
        if (video.getOrgnlRawSn() != null) {
            log.info("[Augment] request blocked — derivative video rawSn={}", rawSn);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "파생 영상은 증강 요청 대상이 아닙니다.", skippedDetails(rawSn));
        }
    }

    /** 생성되지 못한 영상 식별자 — 호출자(관리 화면)가 어떤 영상이 막혔는지 알 수 있게 한다. */
    private static Map<String, Object> skippedDetails(Long rawSn) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("skippedVideoIds", List.of(rawSn));
        return details;
    }

    /**
     * (대표 프레임 × 종류) 증강 요청 처리 — 키 발급 → 키를 실은 PENDING 행 단일 save →
     * AFTER_COMMIT 이벤트 발행. 외부 위탁은 커밋 이후로 위임된다.
     *
     * <p><b>실패 격리</b>: 예외를 <b>삼키지 않고</b> 사유를 로그로 남긴 뒤 {@code false} 를 반환한다.
     * 호출자는 생성 0건을 성공으로 회신하지 않는다(E-ISSUE-09). 예외 원문은 응답으로 나가지 않는다.
     * 단 <b>제약 위반({@link DataIntegrityViolationException})만은 409 로 즉시 종결</b>한다 — 원인이
     * 특정된 충돌을 "생성 0건 → 500" 으로 뭉개면 호출자가 재시도 가능 여부를 판단할 수 없다.
     *
     * @return PENDING 행 생성 성공 여부
     */
    private boolean createOneAugmentRequest(Long rawSn, Long srcSn, String augType,
                                            String regUserNo, String callbackUrl,
                                            PromptPayload prompt) {
        try {
            // 1) idempotencyKey 를 먼저 발급 (UUID 기반 — dataAugSn 비의존).
            //    externalJobId 는 발급하지 않는다 — 외부가 202 응답으로 발급하는 값이다(Phase 7-A1).
            String idempotencyKey = generateIdempotencyKey();

            // 2) 키를 실은 PENDING 행을 단일 save 로 INSERT (이중 save / IDMP_KEY=null orphan 제거).
            //    전송할 prompt 원문도 같은 INSERT 에 실어 "보낸 조건" 을 파생본과 함께 남긴다(V153).
            LsDataAug aug = augRepository.save(LsDataAug.createRequested(
                    srcSn, augType, regUserNo, idempotencyKey, null, prompt.json()));
            Long originAugSn = aug.getDataAugSn();

            // 3) 멱등 키 발급 + 외부 위탁은 요청 트랜잭션 커밋 이후로 위임 (고아 키 방지)
            eventPublisher.publishEvent(new AugmentRequestedItemEvent(
                    originAugSn, rawSn, augType, prompt.fields(),
                    idempotencyKey, callbackUrl, regUserNo));
            return true;
        } catch (DataIntegrityViolationException e) {
            // ⚠ 이 분기는 <중복 증강 차단이 아니다>. 그 정책(UK_LS_DATA_AUG_ACTVTN)은 2026-07-31 폐기됐고
            //    인덱스도 V153 에서 DROP 됐다 — 되살리는 코드로 오해하지 말 것.
            //    남아 있는 제약은 IDMP_KEY UNIQUE(멱등 키 충돌)와 FK(대표프레임 소멸 등 정합 충돌)이며,
            //    둘 다 "요청자의 입력 오류가 아닌 데이터 충돌" 이라 500(INTERNAL_ERROR)이 아니라 409 가
            //    맞다. generic catch 로 흡수시키면 원인 불명 500 이 되어 관측성이 무너진다(구 회귀).
            //    제약 이름·SQL 원문은 응답으로 내보내지 않는다(CWE-209).
            //    ⚠ 로그에도 DB 오류 <원문>을 싣지 않는다(DEV_FIX LOW) — PostgreSQL 의 unique 위반
            //      메시지는 "Key (idmp_key)=(AUG-…) already exists" 형태로 제약명·컬럼·<키 값>을 그대로
            //      담아, 로그 열람 권한만으로 내부 스키마와 실제 키 값이 드러난다. 진단에 필요한 것은
            //      "어떤 제약이 걸렸는가" 이므로 제약명(있으면)과 예외 종류만 남긴다.
            log.warn("[Augment] aug request rejected — data integrity conflict srcSn={} augType={} "
                            + "constraint={} errType={}",
                    srcSn, sanitize(augType), constraintNameOf(e),
                    e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.CONFLICT,
                    "증강 요청 처리 중 데이터 충돌이 발생했습니다. 잠시 후 다시 시도해 주세요.",
                    skippedDetails(rawSn));
        } catch (Exception e) {
            // 건별 격리 — 한 건 실패가 전체 요청을 깨지 않게 한다.
            log.warn("[Augment] aug request item failed (isolated) srcSn={} augType={} err={}",
                    srcSn, sanitize(augType), sanitize(e.getMessage()));
            return false;
        }
    }

    /** 멱등 키 발급 — UUID 기반 (^[A-Za-z0-9_-]+$, ≤64). 형식 위반 시 fail-closed. */
    private String generateIdempotencyKey() {
        String key = "AUG-" + UUID.randomUUID();
        if (key.length() > IDEMPOTENCY_KEY_MAX || !IDEMPOTENCY_KEY_PATTERN.matcher(key).matches()) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "idempotencyKey 발급 형식 오류");
        }
        return key;
    }


    /** 영상의 대표(첫) 프레임 SRC_SN. 프레임이 아직 추출되지 않았으면 {@code null}. */
    private Long findFirstSrcSn(Long rawSn) {
        for (Object[] row : srcRepository.findFirstSrcSnGroupedByRawSn(List.of(rawSn))) {
            if (rawSn.equals(row[0])) {
                return (Long) row[1];
            }
        }
        return null;
    }

    /**
     * 검수 미완료/미존재 영상 ID 목록 반환 (입력 순서 유지).
     */
    private List<Long> findBlockedVideoIds(List<Long> videoIds) {
        // APPROVED 상태인 raw data ID 집합 조회
        Set<Long> approved = new HashSet<>();
        statusRepository.findByRawDataIdIn(videoIds).forEach(s -> {
            if (LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd())) {
                approved.add(s.getRawDataId());
            }
        });
        // 차집합 — 입력 중 APPROVED 가 아닌 ID (row 자체가 없는 경우도 포함)
        return videoIds.stream()
                .filter(id -> !approved.contains(id))
                .toList();
    }

    private void requireReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. */
    private static String sanitize(String value) {
        if (value == null) return null;
        return value.replace('\n', '_').replace('\r', '_');
    }

    /**
     * 제약 위반의 <b>제약명만</b> 뽑는다 — DB 오류 원문(키 값·SQL)을 로그로 흘리지 않기 위함(CWE-209).
     *
     * <p>Hibernate 가 제약명을 파싱해 주면 그것을 쓰고, 아니면 {@code "unknown"} 이다. 원문 폴백을
     * 두지 않는다 — 폴백이 있으면 드라이버·버전에 따라 조용히 원문 로깅으로 되돌아간다.
     */
    private static String constraintNameOf(DataIntegrityViolationException e) {
        for (Throwable c = e; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                return sanitize(cve.getConstraintName());
            }
        }
        return "unknown";
    }
}
