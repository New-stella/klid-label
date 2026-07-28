package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    /** 비식별 누락 신고 구간 판정 — 단일 원천(자체 재구현 금지). */
    private final DeidentReportGate deidentReportGate;
    /** 콜백 URL 조립 — 요청 경로와 재개 경로가 같은 값을 만들게 하는 단일 원천. */
    private final AugmentCallbackUrlResolver callbackUrlResolver;

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
     * @throws CustomException FORBIDDEN(WORKER 등), INVALID_INPUT(단건 계약 위반),
     *                         NOT_REVIEWED(미검수), PRECONDITION_FAILED(신고 구간·프레임 미추출),
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
                rawSn, representativeSrcSn, augType, regUserNo, callbackUrl);
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
     *
     * @return PENDING 행 생성 성공 여부
     */
    private boolean createOneAugmentRequest(Long rawSn, Long srcSn, String augType,
                                            String regUserNo, String callbackUrl) {
        try {
            // 1) idempotencyKey 를 먼저 발급 (UUID 기반 — dataAugSn 비의존).
            //    externalJobId 는 발급하지 않는다 — 외부가 202 응답으로 발급하는 값이다(Phase 7-A1).
            String idempotencyKey = generateIdempotencyKey();

            // 2) 키를 실은 PENDING 행을 단일 save 로 INSERT (이중 save / IDMP_KEY=null orphan 제거)
            LsDataAug aug = augRepository.save(
                    LsDataAug.createRequested(srcSn, augType, regUserNo, idempotencyKey, null));
            Long originAugSn = aug.getDataAugSn();

            // 3) 멱등 키 발급 + 외부 위탁은 요청 트랜잭션 커밋 이후로 위임 (고아 키 방지)
            eventPublisher.publishEvent(new AugmentRequestedItemEvent(
                    originAugSn, rawSn, augType, idempotencyKey, callbackUrl, regUserNo));
            return true;
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
}
