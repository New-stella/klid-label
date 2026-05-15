package kr.co.cudo.authoring.label.dto;

import kr.co.cudo.authoring.label.entity.LsLabelAttr;

/**
 * 라벨 속성 정의 응답 DTO (CVAT-Like 라벨 풀 포팅 Phase 3).
 *
 * <p>Entity 의 timestamp/등록자 등 내부 정보는 노출하지 않는다.
 */
public record LabelAttrResponse(
        Long attrId,
        Long labelId,
        String name,
        String inputType,
        String valuesJson,
        String defaultVal,
        String mutable,
        Integer sortNo,
        String useYn
) {

    /** Entity → Response 변환 정적 팩토리. */
    public static LabelAttrResponse from(LsLabelAttr e) {
        return new LabelAttrResponse(
                e.getAttrId(),
                e.getLabelId(),
                e.getName(),
                e.getInputType(),
                e.getValuesJson(),
                e.getDefaultVal(),
                e.getMutable(),
                e.getSortNo(),
                e.getUseYn()
        );
    }
}
