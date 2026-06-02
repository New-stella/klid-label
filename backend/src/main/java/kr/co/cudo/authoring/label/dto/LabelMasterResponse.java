package kr.co.cudo.authoring.label.dto;

import kr.co.cudo.authoring.label.entity.LsLabel;

/**
 * 라벨 마스터 응답 DTO.
 *
 * <p>Entity 의 timestamp/등록자 등 내부 정보는 노출하지 않는다.
 */
public record LabelMasterResponse(
        Long labelId,
        String name,
        String color,
        String type,
        Integer sortNo,
        String useYn
) {

    /** Entity → Response 변환 정적 팩토리. */
    public static LabelMasterResponse from(LsLabel e) {
        return new LabelMasterResponse(
                e.getLabelId(),
                e.getLabelNm(),
                e.getColrVl(),
                e.getLabelTypeCd(),
                e.getSortSeq(),
                e.getUseYn()
        );
    }
}
