package kr.co.cudo.authoring.preset.dto;

/**
 * 프리셋 코드 조회 뷰 (application 레이어) — 라벨 마스터 실시간 join 결과.
 *
 * <p>라벨명/형태는 저장 스냅샷이 아니라 {@code labelId} 로 마스터를 조회해 파생한 값이다.
 *
 * <ul>
 *   <li>{@code linked=true}  : labelId 가 활성(USE_YN='Y') 마스터에 존재 → labelName/labelType/토글은 마스터 파생.</li>
 *   <li>{@code linked=false} : labelId null 또는 마스터 미존재/soft delete → labelName 은 legacy 코드({@code code}),
 *       labelType/토글은 미적용(null/false).</li>
 * </ul>
 *
 * @param dtctTypeCd 이 라벨에 매핑된 AI 검출 클래스 코드({@code LS_LABEL.DTCT_TYPE_CD}, COCO 영문명).
 *                   미매핑이거나 미연결이면 {@code null} 이고, 그 라벨은 검출 결과에 귀속되지 않는다.
 *                   매핑을 소유하는 것은 프리셋이 아니라 <b>라벨 마스터</b>이므로 마스터에서 지정하면
 *                   그 라벨을 담은 기존 프리셋들이 함께 실효해진다. ★불리언이 아니라 <b>코드값</b>이다 —
 *                   라벨 마스터 조회가 같은 개념을 코드값으로 내리고 있어 표현을 맞춘다.
 */
public record PresetCodeView(
        Long labelId,
        String code,
        String labelName,
        String labelType,
        boolean linked,
        boolean bboxEnabled,
        boolean polygonEnabled,
        String dtctTypeCd
) {
}
