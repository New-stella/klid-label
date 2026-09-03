package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;

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

    /**
     * 공용 라벨 원장 행 + 파싱된 좌표로 응답 생성. 창구·화면의 이름({@code uldLblSn}·{@code uldFrmeSn})은
     * 흡수 뒤에도 그대로 둔다 — 가리키는 것이 바뀌었을 뿐 계약을 깰 이유가 없다.
     *
     * <p>라벨명은 <b>비어 있을 수 있다</b>(V29) — 포털 라벨은 마스터를 참조하지 않는 자유 입력이라
     * 이름 없이 도형만 그리는 것이 정상이다. 기본값을 지어 채우지 않는다.
     */
    public static PortalUploadLabelResponse of(LsDataLbl lbl, List<List<Double>> points) {
        return new PortalUploadLabelResponse(
                lbl.getLblSn(),
                lbl.getSrcSn(),
                lbl.getLblTypeCd(),
                lbl.getLabelNm(),
                points,
                lbl.getRegDt(),
                lbl.getMdfcnDt());
    }
}
