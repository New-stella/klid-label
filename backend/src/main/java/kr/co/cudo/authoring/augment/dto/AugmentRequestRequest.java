package kr.co.cudo.authoring.augment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.integration.dto.GenAiContract;

import java.util.List;

/**
 * 외부 SFR-07 증강 시스템 요청 본문 — 「생성형 AI API 연동명세서 v1.3」 정합.
 *
 * <p>검수 완료된 영상(LsRawDataStatus.dataSttsCd='APPROVED')만 증강 요청 가능하며,
 * 본 검증은 Service 레이어에서 수행한다. DTO 단계에서는 형식·허용 코드만 검증한다.
 *
 * <p>해상도 변경(RESOLUTION)은 저작도구가 직접 수행하므로(RQ-SFR-06-03) 외부 증강 위탁
 * 유형에서 제외한다. 외부 증강은 날씨·계절·시간 3종(WINTER/NIGHT/RAIN)만 위탁한다.
 *
 * <p>단일 선택 계약(2026-06): 한 요청은 <b>영상 1건 + 종류 1개</b>만 처리한다. FE 가
 * 영상 1건·종류 1개만 전송하는 단일 선택 UI 와 정렬하기 위해 배열 모양은 유지하되
 * 정확히 길이 1 만 허용한다(@NotEmpty + @Size(max=1)). 2건 이상이면 400 으로 거부한다.
 *
 * <h3>★ 세 축은 서로를 유추하지 않는다 (2026-08-27 사용자 지적)</h3>
 * <table>
 *   <tr><th>축</th><th>필드</th><th>값</th></tr>
 *   <tr><td>증강 종류(저작도구 내부 식별자)</td><td>{@link #types}</td><td>WINTER / NIGHT / RAIN</td></tr>
 *   <tr><td>이벤트 유형(벤더 계약값)</td><td>{@link #evntType}</td><td>FLOOD / WILDFIRE</td></tr>
 *   <tr><td>생성 조건(벤더 계약값)</td><td>{@link #mtdt}</td><td>다섯 축의 허용 코드</td></tr>
 * </table>
 * <p>이름이 비슷해 섞이기 쉬우나 <b>전혀 다른 값이고 조달처도 다르다</b>.
 * {@link #types} 는 파생 산출물의 저장 경로({@code .../{augTypeCd}.mp4})와 해상도 파생 네임스페이스
 * ({@code RESL_} 접두) 판별에 쓰이므로 <b>반드시 enum 3종으로 닫혀 있어야</b> 하고, 다른 두 축에서
 * <b>파생하지 않는다</b> — 흘러들면 경로 순회(CWE-22)와 {@code RESL_} 침범(검수 우회)이 동시에 열린다.
 *
 * <h3>v1.3 — 생성 조건은 {@code mtdt}, 자유 지시문은 {@code prompt}(문자열)</h3>
 * <p>구 계약(v1.1)의 5필드 객체 {@code prompt} 는 <b>받지 않는다</b>. 값도 자유 문자열이 아니라
 * 허용 코드이며 그 단일 원천은 {@link AugmentPrompts} 다(여기에 사본을 만들지 않는다).
 *
 * @param videoIds    검수 완료된 영상 ID — 정확히 1건(양수)
 * @param types       요청 증강 유형 — WINTER / NIGHT / RAIN 중 정확히 1개
 * @param evntType    외부 이벤트 유형 — FLOOD / WILDFIRE (필수)
 * @param evntSubtype 침수 세부 유형 — 선택. {@code evntType=FLOOD} 일 때만 허용
 * @param mtdt        구조화 생성 조건 — 다섯 항목 전부 필수(우리 규칙)
 * @param prompt      자유 지시문 — 선택, 1000자 이내
 * @design API-060
 */
public record AugmentRequestRequest(
        @NotEmpty(message = "videoIds 는 필수이며 비어있을 수 없습니다.")
        @Size(max = 1, message = "영상은 한 번에 1건만 증강 요청할 수 있습니다.")
        List<@NotNull @Positive Long> videoIds,

        @NotEmpty(message = "types 는 필수이며 비어있을 수 없습니다.")
        @Size(max = 1, message = "증강 종류는 한 번에 1개만 선택할 수 있습니다.")
        List<@NotNull AugmentTypeCode> types,

        @Schema(description = "외부 생성형 AI 이벤트 유형. 영상의 관제 이벤트 코드에서 서버가 변환하지 "
                + "않고 요청자가 고른 값을 그대로 싣는다(두 분류 축이 달라 자동 변환은 추정이 된다).",
                example = "FLOOD", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "evntType 은 필수입니다.")
        GenAiContract.EventType evntType,

        @Schema(description = "침수 세부 유형. 선택이며 evntType=FLOOD 일 때만 허용한다"
                + "(계약에 산불 세부 코드가 정의돼 있지 않다).", example = "ROAD_FLOOD")
        GenAiContract.FloodSubtype evntSubtype,

        @NotNull(message = "mtdt 는 필수입니다.")
        @Valid
        Mtdt mtdt,

        @Schema(description = "자유 지시문. 선택이며 1000자를 넘으면 잘라내지 않고 400 이다. "
                + "제어문자·보이지 않는 문자는 제거 후 저장한다.",
                example = "원본 카메라 시점과 도로 구조를 유지해줘.")
        @Size(max = AugmentPrompts.MAX_PROMPT_LENGTH,
                message = "prompt 는 1000자를 넘을 수 없습니다.")
        String prompt
) {
    /**
     * 증강 유형 enum — DTO 바인딩 단계에서 잘못된 값 차단 (CWE-20 Input Validation).
     * Jackson 이 enum 매칭 실패 시 400 응답이 자동 반환된다.
     */
    public enum AugmentTypeCode {
        WINTER, NIGHT, RAIN
    }

    /**
     * 침수 세부 유형은 침수일 때만 — 산불과 함께 오면 400 (계약에 산불 세부 코드가 없다).
     *
     * <p>DTO 단계에서 끊는 이유는 이 조합이 <b>순수 입력 형식</b> 문제라서다. 서비스도 같은 규칙을
     * fail-closed 로 재확인한다(서비스를 직접 부르는 경로가 우회하지 못하게).
     */
    @AssertTrue(message = "evntSubtype 은 evntType=FLOOD 일 때만 지정할 수 있습니다.")
    @Schema(hidden = true)
    public boolean isEvntSubtypeAllowed() {
        return evntSubtype == null || evntType == GenAiContract.EventType.FLOOD;
    }

    /**
     * 외부로 전송할 구조화 생성 조건 — v1.3 §4.1 최상위 {@code mtdt}.
     *
     * <p><b>다섯 항목 전부 필수</b>(2026-07-31 사용자 확정, 2026-08-27 재확인). 벤더 계약은 "최소 1개"
     * 지만 하나라도 비면 벤더가 어떤 기본값으로 채울지 우리가 알 수 없어 결과가 비결정적이 된다 —
     * <b>더 엄격한 쪽이 의도된 선택</b>이므로 "계약이 선택이니 완화하자" 로 되돌리지 말 것.
     *
     * <p><b>값은 허용 코드다</b>. 구 서술("계약이 허용값을 규정하지 않으므로 우리가 좁히지 않는다 —
     * 길이·공백만 형식 검증한다")은 v1.3 에서 <b>사실이 아니게 되어 폐기</b>됐다. 다섯 축 전부의 코드가
     * 닫혀 있고 코드 밖 값은 벤더에서 {@code 400 INVALID_PARAMETER} 로 되돌아온다.
     *
     * <p>허용 코드의 단일 원천은 {@link AugmentPrompts} 의 enum 이며 <b>여기에 사본을 두지 않는다</b> —
     * 사본을 두면 계약이 늘 때 한쪽만 고쳐져 조용히 갈라진다. enum 이라 Jackson 이 코드 밖 값을
     * 바인딩 단계에서 거부하므로(400), 자유 문자열 시절 필요했던 정규화·길이 검증이 필요 없다.
     *
     * @param time     시간대
     * @param season   계절
     * @param weather  날씨
     * @param terrain  지형
     * @param severity 심각도
     */
    public record Mtdt(
            @Schema(description = "시간대", example = "NIGHT",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "mtdt.time 은 필수입니다.")
            AugmentPrompts.Time time,

            @Schema(description = "계절", example = "WINTER",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "mtdt.season 은 필수입니다.")
            AugmentPrompts.Season season,

            @Schema(description = "날씨", example = "RAIN",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "mtdt.weather 는 필수입니다.")
            AugmentPrompts.Weather weather,

            @Schema(description = "지형", example = "ROAD",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "mtdt.terrain 은 필수입니다.")
            AugmentPrompts.Terrain terrain,

            @Schema(description = "심각도", example = "HIGH",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "mtdt.severity 는 필수입니다.")
            AugmentPrompts.Severity severity
    ) {
    }
}
