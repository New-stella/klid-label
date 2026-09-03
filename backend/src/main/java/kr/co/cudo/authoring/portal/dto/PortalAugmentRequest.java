package kr.co.cudo.authoring.portal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;

/**
 * 포털 채널 증강 요청 본문 — {@code POST /v1/portal/uploads/{uldSn}/augments}.
 *
 * <h3>★ 생성 조건은 <b>다섯 항목 전부 필수 · 닫힌 값역</b>이다 (2026-09-03 확정 · ADR-061)</h3>
 * <p>시간대·계절·날씨·지형·심각도를 전부 받으며 값은 허용 코드로 닫혀 있다. 하나라도 비거나 코드
 * 밖이면 입력 오류로 거부한다. 다섯을 모두 요구하는 것은 <b>이 창구의 규칙</b>이다 — 외부 계약 자체는
 * 최소 한 항목만 요구하지만, 하나라도 비면 위탁받는 쪽이 어떤 기본값으로 채울지 이쪽에서 알 수 없어
 * 같은 요청의 결과가 비결정적이 된다. 더 엄격한 쪽이 의도된 선택이므로 완화하지 말 것.
 *
 * <p>⚠ <b>구 서술 폐기 — 근거가 반대 방향이 됐다.</b> 이 자리에는
 * <i>"조건을 이루는 개별 항목과 그 허용 값역은 이 산출물에서 정하지 않는다"</i> 와
 * <i>"내부 채널 증강 요청 창구의 조건 항목과 값역을 그대로 옮겨 오지 말 것"</i> 이 근거로 적혀 있었다.
 * <b>두 문장 모두 폐기됐다</b>(ADR-061) — 두 채널의 생성 조건을 <b>같은 항목·같은 값역</b>으로 한다는
 * 것이 확정됐다. 그 문장을 근거로 값역을 다시 열지 말 것: 열면 채울 값이 없어 요청 조작이 다시
 * 성립하지 않는다(빈 조건은 거부되는데 채울 값이 정해져 있지 않던 상태로 되돌아간다).
 *
 * <h3>값역의 단일 원천은 {@link AugmentPrompts} 다 — 여기에 사본을 두지 않는다</h3>
 * <p>enum 사본을 만들면 계약이 늘 때 한쪽만 고쳐져 조용히 갈라진다. enum 이라 코드 밖 값은
 * <b>바인딩 단계에서 400</b> 이므로 자유 문자열 시절 필요했던 정규화·길이 검증이 이 다섯 항목에는
 * 필요 없다.
 *
 * <h3>★ 요청자가 고르지 않는 것 — 자리 자체를 두지 않는다</h3>
 * <p><b>이벤트 유형 · 세부 유형 · 증강 종류</b>는 이 본문에 없다. 서버가 위탁 바디를 만들 때 이벤트
 * 유형에 중립값을 고정으로 채우고 세부 유형은 아예 보내지 않으며, 증강 종류는 단일값으로 고정한다.
 * 필드를 두지 않으면 그 키가 위탁으로 실릴 경로가 <b>구조적으로 존재하지 않는다</b> —
 * <b>되살리지 말 것</b>.
 *
 * <h3>모르는 항목도 드러낸다</h3>
 * <p>이 계약은 <b>아는 다섯 항목만</b> 담는다. 요청 본문에 이 계약 밖의 키가 실려도 그것을 조용히
 * 흘려보내 위탁으로 중계하지 않는다 — 나가는 값은 아래 다섯 항목에서만 조립된다.
 *
 * <p>대상 영상은 경로가 정하므로 본문에 다시 싣지 않는다.
 *
 * @param generationCondition 증강 생성 조건 — 다섯 항목 전부 필수. 래퍼 이름은 이 창구가 쓰는 이름이며
 *                            관제 채널의 벤더 계약 필드명으로 통일하지 않는다(ADR-061)
 * @param prompt              자유 지시문 — <b>선택</b>이며 조건과 분리한 최상위 <b>문자열</b>이다.
 *                            그 자리에 객체를 실으면 400 이고, 1000자를 넘으면 잘라내지 않고 400 이다
 * @design API-231
 * @design ADR-061
 * @design ADR-059
 */
public record PortalAugmentRequest(
        @Schema(description = "증강 생성 조건. 다섯 항목 전부 필수이며 값역은 허용 코드로 닫혀 있다. "
                + "접수 시 보관되어 요청 현황 목록·단건 조회에서 같은 값으로 되돌아온다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "증강 생성 조건(generationCondition)은 필수입니다.")
        @Valid
        GenerationCondition generationCondition,

        @Schema(description = "자유 지시문. 선택이며 1000자를 넘으면 잘라내지 않고 400 이다. "
                + "객체를 실으면 400 이다. 제어문자·보이지 않는 문자는 제거 후 저장한다.",
                example = "원본 카메라 시점과 도로 구조를 유지하고 눈 내리는 겨울 야간 장면으로 변경해줘.")
        @Size(max = AugmentPrompts.MAX_PROMPT_LENGTH,
                message = "증강 지시문(prompt)은 1000자를 넘을 수 없습니다.")
        String prompt
) {

    /**
     * 증강 생성 조건 다섯 항목 — 전부 필수, 값역은 허용 코드로 닫혀 있다.
     *
     * <p>허용 코드의 단일 원천은 {@link AugmentPrompts} 이며 <b>여기에 사본을 두지 않는다</b>.
     *
     * @param time     시간대
     * @param season   계절
     * @param weather  날씨
     * @param terrain  지형
     * @param severity 심각도
     */
    public record GenerationCondition(
            @Schema(description = "시간대", example = "NIGHT",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "generationCondition.time 은 필수입니다.")
            AugmentPrompts.Time time,

            @Schema(description = "계절", example = "WINTER",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "generationCondition.season 은 필수입니다.")
            AugmentPrompts.Season season,

            @Schema(description = "날씨", example = "SNOW",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "generationCondition.weather 는 필수입니다.")
            AugmentPrompts.Weather weather,

            @Schema(description = "지형", example = "ROAD",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "generationCondition.terrain 은 필수입니다.")
            AugmentPrompts.Terrain terrain,

            @Schema(description = "심각도", example = "HIGH",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "generationCondition.severity 는 필수입니다.")
            AugmentPrompts.Severity severity
    ) {
    }
}
