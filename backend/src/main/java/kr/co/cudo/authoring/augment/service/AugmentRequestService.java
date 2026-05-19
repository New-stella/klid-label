package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest.AugmentTypeCode;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V1.5 SFR-07 — 외부 증강 시스템 요청 서비스.
 *
 * <p>저작도구는 검수 완료된 영상(LsRawDataStatus.dataSttsCd='APPROVED')만 증강 요청 가능.
 * 외부 시스템 미연동 상태이므로 jobId 는 placeholder 시퀀스로 발급한다.
 *
 * <p>RBAC: REVIEWER 만 호출 가능 (Service 이중 검증 + Controller @PreAuthorize).
 * <p>트랜잭션: read-only 검증만 수행 — 본 트랜잭션은 영상 검수 상태를 변경하지 않는다.
 *           외부 호출은 best-effort 패턴 (실패 시 로그만 남기고 사용자 응답에는 영향 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AugmentRequestService {

    private final LsRawDataStatusRepository statusRepository;
    private final ExternalAugmentClient externalClient;

    /**
     * placeholder jobId 시퀀스 — 외부 SFR-07 연동 전까지 사용.
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

        // 3) 외부 SFR-07 통보 (best-effort — 실패해도 사용자 응답엔 영향 없음)
        try {
            externalClient.requestAugment(videoIds, types);
        } catch (Exception e) {
            log.warn("[Augment] external request failed actor={} videoCount={} err={}",
                    sanitize(actor.sub()), videoIds.size(), sanitize(e.getMessage()));
        }

        long jobId = jobIdSeq.incrementAndGet();
        LocalDateTime requestedAt = LocalDateTime.now();
        log.info("[Augment] requested jobId={} actor={} videoCount={} typeCount={}",
                jobId, sanitize(actor.sub()), videoIds.size(), types.size());
        return new AugmentRequestResponse(jobId, requestedAt, videoIds.size(), types.size());
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
