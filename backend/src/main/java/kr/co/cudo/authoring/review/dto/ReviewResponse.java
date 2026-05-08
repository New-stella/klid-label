package kr.co.cudo.authoring.review.dto;

import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;

import java.time.LocalDateTime;

/**
 * 검수 워크플로우 단건 응답.
 *
 * <p>BE 원본 컬럼(pjtId/dataSttsCd/version/updDt) 외에 FE 호환 alias 필드를 함께 노출해
 * 화면 측 필드명 매핑을 단순화한다.
 * <ul>
 *   <li>{@code id}            = {@code videoId} (FE 라우팅 PK)</li>
 *   <li>{@code cctvName}      = video lookup 결과(미조인 시 "video #N" fallback)</li>
 *   <li>{@code workerId}      = LS_PJT_USER_AUTHRT lookup (LABELER) — 없으면 null</li>
 *   <li>{@code workerName}    = MNG_ACCT_USER lookup — 없으면 ""</li>
 *   <li>{@code submittedAt}   = {@code updDt}</li>
 *   <li>{@code labelCount}    = LS_DATA_LBL count by srcSn for this rawSn — 없으면 0</li>
 *   <li>{@code status}        = FE ReviewStatus 코드 (BE dataSttsCd → FE 코드 매핑)</li>
 * </ul>
 * 기존 필드(pjtId/videoId/dataSttsCd/version/updDt)는 backward-compat 유지.
 */
public record ReviewResponse(
        // FE 호환 alias
        Long id,
        String cctvName,
        Long workerId,
        String workerName,
        LocalDateTime submittedAt,
        Long labelCount,
        String status,
        // BE 원본 필드 (호환 유지)
        Long pjtId,
        Long videoId,
        String dataSttsCd,
        Long version,
        LocalDateTime updDt
) {

    /** 단순 매핑 — lookup 인자 없이 status alias 만 변환. */
    public static ReviewResponse from(LsPjtDataStts stts) {
        return from(stts, null, null, null, 0L);
    }

    /** 보강 매핑 — 서비스 레이어에서 cctvName/workerId/workerName/labelCount 를 함께 주입. */
    public static ReviewResponse from(LsPjtDataStts stts,
                                      String cctvName,
                                      Long workerId,
                                      String workerName,
                                      Long labelCount) {
        Long videoId = stts.getId().getRawDataId();
        String resolvedCctv = (cctvName != null && !cctvName.isBlank())
                ? cctvName : ("video #" + videoId);
        String resolvedWorkerName = workerName == null ? "" : workerName;
        Long resolvedLabelCount = labelCount == null ? 0L : labelCount;
        String feStatus = mapToFeStatus(stts.getDataSttsCd());
        return new ReviewResponse(
                videoId,
                resolvedCctv,
                workerId,
                resolvedWorkerName,
                stts.getUpdDt(),
                resolvedLabelCount,
                feStatus,
                stts.getId().getPjtId(),
                videoId,
                stts.getDataSttsCd(),
                stts.getVersion(),
                stts.getUpdDt()
        );
    }

    /**
     * BE dataSttsCd → FE ReviewStatus 코드 매핑.
     *
     * <ul>
     *   <li>PENDING   → REVIEW_PENDING</li>
     *   <li>IN_REVIEW → REVIEWING</li>
     *   <li>APPROVED  → COMPLETED</li>
     *   <li>REJECTED  → REJECTED</li>
     *   <li>그 외 (ASSIGNED 등) → 원본 그대로 (StatusBadge 가 폴백 처리)</li>
     * </ul>
     */
    private static String mapToFeStatus(String dataSttsCd) {
        if (dataSttsCd == null) return null;
        return switch (dataSttsCd) {
            case "PENDING"   -> "REVIEW_PENDING";
            case "IN_REVIEW" -> "REVIEWING";
            case "APPROVED"  -> "COMPLETED";
            case "REJECTED"  -> "REJECTED";
            default          -> dataSttsCd;
        };
    }
}
