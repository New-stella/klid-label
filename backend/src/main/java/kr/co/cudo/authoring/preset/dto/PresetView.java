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
 *
 * @param effective 이 프리셋이 <b>실효</b>하는지 — 담긴 라벨 중 AI 검출 클래스에 매핑된 것이 하나라도
 *                  있으면 {@code true}. {@code false} 면 프리셋이 존재해도 그 이벤트 유형의 오토라벨링은
 *                  보류되며(라벨 0건 프리셋은 보류가 아니라 오토라벨 제외 선언이다), 그 사실이 조회에
 *                  드러나지 않으면 운영자는 프리셋을 등록해 놓고도 왜 적용되지 않는지 알 수 없다.
 *                  ★판정은 오토라벨 보류 판정과 <b>같은 판정을 재사용</b>해 얻으며 프리셋 도메인이 다시
 *                  유도하지 않는다.
 */
public record PresetView(
        Long presetId,
        String eventTypeCd,
        String eventTypeNm,
        LocalDateTime regDt,
        LocalDateTime mdfcnDt,
        List<PresetCodeView> codes,
        boolean effective
) {
}
