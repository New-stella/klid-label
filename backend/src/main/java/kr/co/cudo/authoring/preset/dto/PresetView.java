package kr.co.cudo.authoring.preset.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 프리셋 조회 뷰 (application 레이어) — 대상 이벤트 + 라벨 마스터 join 된 코드 목록.
 *
 * <p>PresetService 가 마스터 배치 조회로 조립하여 Controller 에 반환한다(Controller 는 DTO 매핑만).
 *
 * <p>프리셋은 이름·설명을 갖지 않는다(V17). 사람이 읽는 이름은 {@code eventTypeNm} — 이벤트유형
 * 마스터의 표시명을 서버가 해석해 실어 준다.
 */
public record PresetView(
        Long presetId,
        String eventTypeCd,
        String eventTypeNm,
        LocalDateTime regDt,
        LocalDateTime mdfcnDt,
        List<PresetCodeView> codes
) {
}
