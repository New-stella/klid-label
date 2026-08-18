package kr.co.cudo.authoring.review.dto;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.video.dto.CctvDisplayNamePolicy;

import java.time.LocalDateTime;

/**
 * 검수 워크플로우 단건 응답.
 *
 * <p>BE 원본 컬럼(dataSttsCd/version/updDt) 외에 FE 호환 alias 필드를 함께 노출해
 * 화면 측 필드명 매핑을 단순화한다.
 * <ul>
 *   <li>{@code id}            = {@code videoId} (FE 라우팅 PK)</li>
 *   <li>{@code cctvName}      = video lookup 결과(미조인 시 {@code 영상 #N} 폴백 —
 *       판정 단일 원천 {@link CctvDisplayNamePolicy})</li>
 *   <li>{@code workerId}      = LS_TASK_ALTMNT lookup (LABELER) — 없으면 null</li>
 *   <li>{@code workerName}    = LS_ACNT_USER lookup — 없으면 ""</li>
 *   <li>{@code submittedAt}   = {@code updDt}</li>
 *   <li>{@code labelCount}    = LS_DATA_LBL count by srcSn for this rawSn — 없으면 0</li>
 *   <li>{@code status}        = FE ReviewStatus 코드 (BE dataSttsCd → FE 코드 매핑)</li>
 *   <li>{@code eventName}     = video lookup 결과 EVNT_TYPE_CD — 없으면 null</li>
 *   <li>{@code eventTypeCd}   = video lookup 결과 EVNT_TYPE_CD — 없으면 null</li>
 *   <li>{@code needsRecheck}  = {@code LsRawDataStatus.REVLT_YN}(V177) — 검수 승인 이후 라벨/메타가
 *       수정되어 재검토가 필요한가(Phase 7b, API-008/API-014). {@code false} 가 기본값이며 승인 상태가
 *       아닌 영상도 항상 값을 갖는다(재검토 축은 승인 여부와 별개로 조회 가능).</li>
 * </ul>
 * BE 원본 필드(videoId/dataSttsCd/version/updDt)는 backward-compat 유지.
 * {@code needsRecheck} 는 <b>추가 필드</b>다 — 기존 필드·타입·상태코드는 변경하지 않는다.
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
        Long videoId,
        String dataSttsCd,
        Long version,
        LocalDateTime updDt,
        // 이벤트 메타 (Phase 1 enrich) — 마지막에 추가하여 backward-compat 유지
        String eventName,
        String eventTypeCd,
        // Phase 7b — 재검토 필요 표시(V177 REVLT_YN). 마지막에 추가하여 backward-compat 유지.
        boolean needsRecheck
) {

    /** 단순 매핑 — lookup 인자 없이 status alias 만 변환. */
    public static ReviewResponse from(LsRawDataStatus stts) {
        return from(stts, null, null, null, 0L, null, null);
    }

    /**
     * 보강 매핑 (eventName 미주입 호환) — 서비스 레이어에서 cctvName/workerId/workerName/labelCount 만 주입.
     * eventName/eventTypeCd 는 null 폴백.
     */
    public static ReviewResponse from(LsRawDataStatus stts,
                                      String cctvName,
                                      Long workerId,
                                      String workerName,
                                      Long labelCount) {
        return from(stts, cctvName, workerId, workerName, labelCount, null, null);
    }

    /**
     * 보강 매핑 (정식 — Phase 1) — 서비스 레이어에서
     * cctvName/workerId/workerName/labelCount/eventName/eventTypeCd 를 함께 주입.
     */
    public static ReviewResponse from(LsRawDataStatus stts,
                                      String cctvName,
                                      Long workerId,
                                      String workerName,
                                      Long labelCount,
                                      String eventName,
                                      String eventTypeCd) {
        Long videoId = stts.getRawDataId();
        // 표시명 폴백은 CctvDisplayNamePolicy 단독 판정이다. 구 표기 "video #N" 은 폐기 —
        // 같은 영상이 검수목록에서만 다른 이름으로 보였다(작업목록·영상목록은 "영상 #N").
        String resolvedCctv = CctvDisplayNamePolicy.resolve(cctvName, null, videoId);
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
                videoId,
                stts.getDataSttsCd(),
                stts.getVersion(),
                stts.getUpdDt(),
                eventName,
                eventTypeCd,
                stts.needsRecheck()
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
