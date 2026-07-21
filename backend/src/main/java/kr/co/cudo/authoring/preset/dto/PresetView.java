package kr.co.cudo.authoring.preset.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 프리셋 조회 뷰 (application 레이어) — 프리셋 메타 + 라벨 마스터 join 된 코드 목록.
 *
 * <p>PresetService 가 마스터 배치 조회로 조립하여 Controller 에 반환한다(Controller 는 DTO 매핑만).
 */
public record PresetView(
        Long presetId,
        String presetNm,
        String expln,
        String eventTypeCd,
        LocalDateTime regDt,
        LocalDateTime mdfcnDt,
        List<PresetCodeView> codes
) {
}
