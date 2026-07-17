package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.portal.entity.LsPortalUldLbl;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 4 — 포털 업로드 프레임 라벨 응답 1행 (교체 결과/목록 공용).
 *
 * <p>Entity 직접 노출 금지 정책 준수 — 내부 컬럼(PORTAL_USER_NO 등)은 노출하지 않는다.
 * 좌표는 저장 JSON(POINT_CN)을 파싱한 {@code [[x,y], ...]} 중첩 배열로 반환한다(FE 재사용 호환).
 */
public record PortalUploadLabelResponse(
        Long uldLblSn,
        Long uldFrmeSn,
        String lblTypeCd,
        String label,
        List<List<Double>> points,
        LocalDateTime regDt,
        LocalDateTime mdfcnDt
) {

    /** 엔티티 + 파싱된 좌표로 응답 생성. */
    public static PortalUploadLabelResponse of(LsPortalUldLbl lbl, List<List<Double>> points) {
        return new PortalUploadLabelResponse(
                lbl.getUldLblSn(),
                lbl.getUldFrmeSn(),
                lbl.getLblTypeCd(),
                lbl.getLblNm(),
                points,
                lbl.getRegDt(),
                lbl.getMdfcnDt());
    }
}
