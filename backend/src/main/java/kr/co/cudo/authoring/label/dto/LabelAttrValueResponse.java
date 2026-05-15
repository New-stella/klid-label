package kr.co.cudo.authoring.label.dto;

/**
 * 객체별 속성값 응답 DTO (CVAT-Like 라벨 풀 포팅 Phase 3).
 *
 * <p>LS_LABEL_ATTR 와 조인하여 사람이 읽을 수 있는 name/inputType 동봉.
 */
public record LabelAttrValueResponse(
        Long attrId,
        String name,
        String inputType,
        String value
) {
}
