package kr.co.cudo.authoring.augment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 외부 SFR-07 증강 시스템 요청 본문.
 *
 * <p>검수 완료된 영상(LsRawDataStatus.dataSttsCd='APPROVED')만 증강 요청 가능하며,
 * 본 검증은 Service 레이어에서 수행한다. DTO 단계에서는 형식·범위만 검증한다.
 *
 * <p>해상도 변경(RESOLUTION)은 저작도구가 직접 수행하므로(RQ-SFR-06-03) 외부 증강 위탁
 * 유형에서 제외한다. 외부 증강은 날씨·계절·시간 3종(WINTER/NIGHT/RAIN)만 위탁한다.
 *
 * <p>단일 선택 계약(2026-06): 한 요청은 <b>영상 1건 + 종류 1개</b>만 처리한다. FE 가
 * 영상 1건·종류 1개만 전송하는 단일 선택 UI 와 정렬하기 위해 배열 모양은 유지하되
 * 정확히 길이 1 만 허용한다(@NotEmpty + @Size(max=1)). 2건 이상이면 400 으로 거부한다.
 *
 * <h3>prompt 는 {@code types} 를 대체하지 않는다 (2026-07-31 신설)</h3>
 * <p>구조화 프롬프트 5필드가 추가됐지만 {@link #types} 계약은 <b>그대로 유지</b>된다. 둘은 역할이 다르다:
 * <ul>
 *   <li>{@link #types} — 저작도구 내부 식별자({@code LS_DATA_AUG.AUG_TYPE_CD}). 파생 산출물의 저장
 *       경로({@code .../{augTypeCd}.mp4})와 해상도 파생 네임스페이스({@code RESL_} 접두) 판별에 쓰이므로
 *       <b>반드시 enum 3종으로 닫혀 있어야</b> 한다. 자유 문자열이 여기로 흘러들면 경로 순회(CWE-22)와
 *       {@code RESL_} 침범(검수 우회)이 동시에 열린다 — 그래서 prompt 로부터 유형을 <b>파생하지 않는다</b>.</li>
 *   <li>{@link #prompt} — 외부 생성형 AI 로 그대로 나가는 생성 조건. 「생성형 AI API 연동명세서 v1.1」
 *       §4.1 이 {@code prompt} 를 자유 구조 dict 로만 규정하고 허용값 enum 을 정의하지 않으므로
 *       <b>값을 enum 으로 좁히지 않는다</b>(임의 allowlist 를 만들면 벤더가 지원하는 조건을 우리가
 *       모르는 채로 막게 된다). 대신 길이·공백만 형식 검증한다.</li>
 * </ul>
 *
 * @param videoIds 검수 완료된 영상 ID — 정확히 1건(양수)
 * @param types    요청 증강 유형 — WINTER / NIGHT / RAIN 중 정확히 1개
 * @param prompt   생성 조건 5필드 — 전부 필수
 */
public record AugmentRequestRequest(
        @NotEmpty(message = "videoIds 는 필수이며 비어있을 수 없습니다.")
        @Size(max = 1, message = "영상은 한 번에 1건만 증강 요청할 수 있습니다.")
        List<@NotNull @Positive Long> videoIds,

        @NotEmpty(message = "types 는 필수이며 비어있을 수 없습니다.")
        @Size(max = 1, message = "증강 종류는 한 번에 1개만 선택할 수 있습니다.")
        List<@NotNull AugmentTypeCode> types,

        @NotNull(message = "prompt 는 필수입니다.")
        @Valid
        PromptFields prompt
) {
    /**
     * 증강 유형 enum — DTO 바인딩 단계에서 잘못된 값 차단 (CWE-20 Input Validation).
     * Jackson 이 enum 매칭 실패 시 400 응답이 자동 반환된다.
     */
    public enum AugmentTypeCode {
        WINTER, NIGHT, RAIN
    }

    /**
     * 외부로 전송할 구조화 프롬프트 — 「생성형 AI API 연동명세서 v1.1」 §4.1 {@code prompt} 샘플과 동일 키.
     *
     * <p><b>5필드 전부 필수</b>(사용자 확정). 하나라도 비면 벤더가 어떤 기본값으로 채울지 우리가 알 수
     * 없어 결과가 비결정적이 된다 — 부분 입력을 허용하는 대신 전부 받는다.
     *
     * <p><b>값은 자유 문자열</b>이다. 예시(NIGHT/WINTER/RAIN/ROAD/HIGH)는 어디까지나 예시이며 enum 이
     * 아니다 — 계약이 허용값을 정의하지 않으므로 우리가 임의로 좁히지 않는다(클래스 주석 참조).
     * 대신 <b>길이 상한(50)과 공백 금지</b>로 형식만 닫는다:
     * <ul>
     *   <li>{@code @NotBlank} — 공백만 입력한 값은 "입력했다" 고 볼 수 없다. 통과시키면 벤더에 빈 조건이
     *       나가 위 비결정성이 그대로 발생한다.</li>
     *   <li>{@code @Size(max=50)} — 상한이 없으면 저장 컬럼({@code PROMPT_CN}, VARCHAR(4000))을 넘겨
     *       적재 시점 500 이 되고, 무제한 입력이 외부로 중계된다(CWE-770).</li>
     * </ul>
     *
     * <p>정규화는 Service 가 {@code VisibleTextNormalizer} 로 수행한다 — 제어문자(개행·NUL)뿐 아니라
     * <b>보이지 않는 문자</b>(NBSP·ZWSP·BOM·RLO·U+2028/2029)까지 걷어낸다. 로그 위조(CWE-117)와
     * PostgreSQL NUL 바이트 거부를 막고, 무엇보다 <b>보이지 않는 문자만 채운 값</b>이
     * {@code @NotBlank}({@code trim()} 기준)를 통과해 "빈 조건" 으로 벤더에 나가는 것을 막는다.
     * (구 서술의 {@code ControlCharNormalizer} 는 목록 필터용 <b>다른</b> 단일 원천이며 이 경로가
     * 쓰는 것이 아니다 — 2026-07-31 서술 정정.)
     *
     * @param time     시간대 (예: NIGHT)
     * @param season   계절 (예: WINTER)
     * @param weather  날씨 (예: RAIN)
     * @param terrain  지형 (예: ROAD)
     * @param severity 심각도 (예: HIGH)
     */
    public record PromptFields(
            @Schema(description = "시간대. " + FREE_TEXT_CONTRACT, example = "NIGHT",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "prompt.time 은 필수입니다.")
            @Size(max = MAX_FIELD_LENGTH, message = "prompt.time 은 50자를 넘을 수 없습니다.")
            String time,

            @Schema(description = "계절. " + FREE_TEXT_CONTRACT, example = "WINTER",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "prompt.season 은 필수입니다.")
            @Size(max = MAX_FIELD_LENGTH, message = "prompt.season 은 50자를 넘을 수 없습니다.")
            String season,

            @Schema(description = "날씨. " + FREE_TEXT_CONTRACT, example = "RAIN",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "prompt.weather 는 필수입니다.")
            @Size(max = MAX_FIELD_LENGTH, message = "prompt.weather 는 50자를 넘을 수 없습니다.")
            String weather,

            @Schema(description = "지형. " + FREE_TEXT_CONTRACT, example = "ROAD",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "prompt.terrain 은 필수입니다.")
            @Size(max = MAX_FIELD_LENGTH, message = "prompt.terrain 은 50자를 넘을 수 없습니다.")
            String terrain,

            @Schema(description = "심각도. " + FREE_TEXT_CONTRACT, example = "HIGH",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "prompt.severity 는 필수입니다.")
            @Size(max = MAX_FIELD_LENGTH, message = "prompt.severity 는 50자를 넘을 수 없습니다.")
            String severity
    ) {
        /**
         * Swagger 설명 공통 꼬리말 — <b>enum 이 아니라 자유 문자열</b>임을 FE 개발자에게 명시한다.
         * 「생성형 AI API 연동명세서 v1.1」 §4.1 이 {@code prompt} 를 자유 구조 dict 로만 규정하고
         * 허용값을 정의하지 않으므로, 예시값(NIGHT/WINTER/RAIN/ROAD/HIGH)은 규격서 샘플일 뿐
         * <b>선택지가 아니다</b> — FE 가 이 값들로 셀렉트 박스를 고정하면 벤더가 지원하는 조건을
         * 우리가 모르는 채로 막게 된다.
         */
        static final String FREE_TEXT_CONTRACT =
                "허용값 enum 이 아닌 자유 문자열이며(연동명세서 v1.1 §4.1 이 허용값을 규정하지 않는다) "
                        + "예시는 규격서 샘플값일 뿐 선택지가 아니다. 필수 · 공백 불가 · 50자 이내이며, "
                        + "보이지 않는 문자(제어문자 · NBSP · ZWSP · BOM 등)만 채운 값은 400 으로 거부된다.";

        /**
         * 필드별 길이 상한 — Service 가 같은 값으로 fail-closed 재확인한다(DTO 검증은 컨트롤러 진입에만
         * 적용되므로, 서비스를 직접 부르는 경로가 상한을 우회하지 못하게 한다).
         */
        public static final int MAX_FIELD_LENGTH = 50;
    }
}
