package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest.AugmentTypeCode;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
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
 * 요청 시 distinct (영상 × 종류) 각 건에 대해 다음 선행 상태를 만들어 콜백이 성립하게 한다:
 *
 * <ol>
 *   <li>영상의 대표 프레임(MIN SRC_SN) 조회</li>
 *   <li>idempotencyKey(^[A-Za-z0-9_-]+$, ≤64) + externalJobId(≤128) 를 먼저 발급</li>
 *   <li>{@link LsDataAug#createRequested} 로 키를 실은 PENDING 행을 <b>단일 save</b> 적재 → originAugSn</li>
 *   <li>{@link AugmentRequestedItemEvent} 를 건별 발행 (멱등 키 발급 + 외부 콜백은 커밋 이후로 위임)</li>
 * </ol>
 *
 * <p><b>고아 키 방지 (DEV_FIX HIGH #1)</b>: 멱등 키 allowlist 등록(ledger.recordIssued)과
 * 외부 콜백 컨텍스트 전달(externalClient.requestAugment)은 요청 트랜잭션 안에서 하지 않고,
 * {@code AugmentRequestBridge} 가 {@code @TransactionalEventListener(AFTER_COMMIT)} 로 수신해
 * <b>커밋 확정 후</b>에만 수행한다. 요청 트랜잭션이 롤백되면 aug 행도 멱등 키도 생성되지 않는다.
 *
 * <p>RBAC: REVIEWER 만 호출 가능 (Service 이중 검증 + Controller @PreAuthorize).
 * <p>트랜잭션: 클래스 기본 readOnly, {@link #request} 만 write override (aug 행 INSERT).
 * <p>건별 격리: 한 영상(또는 한 종류)의 처리 실패가 전체 요청을 깨지 않는다(프레임 없는 영상 스킵 등).
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
    private static final int EXTERNAL_JOB_ID_MAX = 128;
    /** 콜백 경로 — 단일 진실원({@link kr.co.cudo.authoring.common.security.HmacWebhookFilter#PATH_AUGMENT}) 참조. */
    public static final String CALLBACK_PATH =
            kr.co.cudo.authoring.common.security.HmacWebhookFilter.PATH_AUGMENT;

    private final LsRawDataStatusRepository statusRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataAugRepository augRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 콜백 base URL — 외부 시스템이 결과를 push 할 엔드포인트의 prefix. */
    @Value("${authoring.webhook.callback-base-url:http://localhost:8080/api}")
    private String callbackBaseUrl;

    /**
     * placeholder jobId 시퀀스 — 외부 SFR-07 연동 전까지 응답 jobId 발급에 사용.
     * 동시성 안전을 위해 AtomicLong 사용. base 는 인스턴스 시작 시각(ms) 으로 충돌 회피.
     */
    private final AtomicLong jobIdSeq = new AtomicLong(System.currentTimeMillis());

    /**
     * 외부 SFR-07 증강 시스템에 검수 완료 영상 + 증강 유형을 요청한다.
     *
     * @param request 영상 ID·유형 목록 (DTO 단계 형식 검증 통과)
     * @param actor   호출자 토큰 (REVIEWER 만 허용)
     * @return jobId / 요청 시각 / distinct 카운트
     * @throws CustomException FORBIDDEN(WORKER 등), NOT_REVIEWED(미검수 영상 포함)
     */
    @Transactional("controlTransactionManager")
    public AugmentRequestResponse request(AugmentRequestRequest request, TokenClaims actor) {
        requireReviewer(actor);

        // 1) distinct 처리 — 입력 중복 정규화 (CWE-20 Input Validation)
        List<Long> videoIds = request.videoIds().stream().distinct().toList();
        List<String> types = request.types().stream()
                .distinct()
                .map(AugmentTypeCode::name)
                .toList();

        // 2) 검수 완료(APPROVED) 검증 — 하나라도 미검수면 전체 거부 (부분 처리 금지)
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

        // 3) 영상별 대표 프레임(MIN SRC_SN) 일괄 조회 (N+1 회피)
        Map<Long, Long> firstSrcSnByRawSn = findFirstSrcSnByRawSn(videoIds);

        // 4) distinct (영상 × 종류) 건별 PENDING 적재 + 멱등 키 발급 + 콜백 컨텍스트 전달.
        //    한 건의 실패가 전체 요청을 깨지 않도록 건별 격리한다.
        String regUserNo = actor.sub();
        String callbackUrl = resolveCallbackUrl();
        int createdCount = 0;
        for (Long rawSn : videoIds) {
            Long representativeSrcSn = firstSrcSnByRawSn.get(rawSn);
            if (representativeSrcSn == null) {
                // 프레임이 없는 영상 — 콜백 시 createAugmentedVideo 가 복사할 원본 프레임이 없다. 건별 스킵.
                log.warn("[Augment] no frame for video — skip rawSn={}", rawSn);
                continue;
            }
            for (String augType : types) {
                if (createOneAugmentRequest(representativeSrcSn, augType, regUserNo, callbackUrl)) {
                    createdCount++;
                }
            }
        }

        long jobId = jobIdSeq.incrementAndGet();
        LocalDateTime requestedAt = LocalDateTime.now();
        log.info("[Augment] requested jobId={} actor={} videoCount={} typeCount={} createdAugCount={}",
                jobId, sanitize(regUserNo), videoIds.size(), types.size(), createdCount);
        return new AugmentRequestResponse(jobId, requestedAt, videoIds.size(), types.size());
    }

    /**
     * 단일 (대표 프레임 × 종류) 증강 요청 처리 — 키 발급 → 키를 실은 PENDING 행 단일 save →
     * 건별 AFTER_COMMIT 이벤트 발행. 멱등 키 allowlist 등록·외부 콜백 전달은 커밋 이후로 위임된다.
     * 건별 격리: 본 메서드 내 예외는 잡아서 false 를 반환하고 다음 건 처리를 계속한다.
     *
     * @return PENDING 행 생성 성공 여부
     */
    private boolean createOneAugmentRequest(Long srcSn, String augType, String regUserNo, String callbackUrl) {
        try {
            // 1) idempotencyKey + externalJobId 를 먼저 발급 (UUID 기반 — dataAugSn 비의존)
            String idempotencyKey = generateIdempotencyKey();
            String externalJobId = generateExternalJobId();

            // 2) 키를 실은 PENDING 행을 단일 save 로 INSERT (이중 save / IDMP_KEY=null orphan 제거)
            LsDataAug aug = augRepository.save(
                    LsDataAug.createRequested(srcSn, augType, regUserNo, idempotencyKey, externalJobId));
            Long originAugSn = aug.getDataAugSn();

            // 3) 멱등 키 발급 + 외부 콜백 컨텍스트 전달은 요청 트랜잭션 커밋 이후로 위임 (고아 키 방지)
            eventPublisher.publishEvent(new AugmentRequestedItemEvent(
                    originAugSn, augType, idempotencyKey, externalJobId, callbackUrl));
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

    /** 외부 작업 ID 발급 — UUID 기반 (≤128). */
    private String generateExternalJobId() {
        String jobId = "JOB-" + UUID.randomUUID();
        if (jobId.length() > EXTERNAL_JOB_ID_MAX) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "externalJobId 발급 형식 오류");
        }
        return jobId;
    }

    private String resolveCallbackUrl() {
        String base = (callbackBaseUrl == null || callbackBaseUrl.isBlank())
                ? "http://localhost:8080/api"
                : callbackBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + CALLBACK_PATH;
    }

    /** 영상별 대표(첫) 프레임 SRC_SN 매핑. 프레임이 없는 영상은 결과에 포함되지 않는다. */
    private Map<Long, Long> findFirstSrcSnByRawSn(Collection<Long> videoIds) {
        Map<Long, Long> result = new HashMap<>();
        if (videoIds.isEmpty()) {
            return result;
        }
        for (Object[] row : srcRepository.findFirstSrcSnGroupedByRawSn(videoIds)) {
            result.put((Long) row[0], (Long) row[1]);
        }
        return result;
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
