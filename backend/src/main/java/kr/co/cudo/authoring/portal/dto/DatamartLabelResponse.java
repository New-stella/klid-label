package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;

/**
 * V2.0 -- 데이터마트 라벨 Load 응답 DTO.
 * Entity(LsDataLbl) 직접 반환 금지 정책 준수.
 */
public record DatamartLabelResponse(
        Long lblSn,
        Long srcSn,
        String lblTypeCd,
        String label,
        String pointsJson,
        String trackId
) {
    public static DatamartLabelResponse from(LsDataLbl e) {
        return new DatamartLabelResponse(
                e.getLblSn(), e.getSrcSn(), e.getLblTypeCd(),
                e.getLabelNm(), e.getPointCn(), e.getTrackId());
    }
}
