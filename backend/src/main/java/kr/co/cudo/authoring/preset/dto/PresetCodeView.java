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
 */
public record PresetCodeView(
        Long labelId,
        String code,
        String labelName,
        String labelType,
        boolean linked,
        boolean bboxEnabled,
        boolean polygonEnabled
) {
}
