package kr.co.cudo.authoring.label.dto;

/**
 * Phase 4 — 트랙 병합 결과.
 *
 * @param rawSn                영상 PK
 * @param fromTrackId          병합된(사라진) 트랙 ID
 * @param toTrackId            병합 대상(유지된) 트랙 ID
 * @param reassignedLabelCount 트랙 ID 가 재지정된 원 키프레임 라벨 수(보간 산출물 제외)
 * @param interpolationApplied 재보간이 실제 대상(자동 BBOX/POLYGON 트랙)을 가져 동작했는지 여부.
 *                             수동/SEGMENT/SKELETON 트랙이면 false — 계약↔실제 불일치 노출 방지.
 * @param interpolatedRowCount 재보간으로 새로 생성된 보간 row 수(interpolationApplied=false 면 0)
 */
public record TrackMergeResponse(
        Long rawSn,
        String fromTrackId,
        String toTrackId,
        int reassignedLabelCount,
        boolean interpolationApplied,
        int interpolatedRowCount
) {
}
