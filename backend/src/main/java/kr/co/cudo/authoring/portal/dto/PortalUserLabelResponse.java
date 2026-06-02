package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;

import java.time.LocalDateTime;

public record PortalUserLabelResponse(
        Long userLblSn,
        Long sourceRawSn,
        Long sourceSrcSn,
        String lblTypeCd,
        String label,
        String points,
        LocalDateTime createdAt
) {
    public static PortalUserLabelResponse from(LsPortalUserLabel e) {
        return new PortalUserLabelResponse(
                e.getUserLblSn(), e.getSrcRawSn(), e.getSrcDataSrcSn(),
                e.getLblTypeCd(), e.getLabelNm(), e.getPointCn(), e.getRegDt());
    }
}
