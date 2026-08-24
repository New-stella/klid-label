package kr.co.cudo.authoring.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 외부 시계열 분석 결과 콜백 페이로드 — 확정 계약(KLID 연동 API v1.1.0) §2.7 정합. [req: R3]
 *
 * <p>{@code POST /v1/vlm/callback} 요청 본문:
 * <pre>
 * // 성공
 * { "request_id": "00000002", "status": "completed",
 *   "results": { "description": "- 장소: 주택가 골목\n- 날씨: 흐림 ..." } }
 * // 실패
 * { "request_id": "00000002", "status": "failed", "error": "추론 실패: ..." }
 * </pre>
 *
 * <h3>★ 판정 항목({@code detected}·{@code accuracy})은 오지 않는다</h3>
 * <p>규격 §2.8 이 그 두 항목을 <b>판정 창구 전용</b>으로 못 박는다. 우리가 연동하는 묘사·추가 질문
 * 두 창구는 {@code description} 하나만 돌려준다(§3.2·§3.3). 따라서 그 두 필드를 받지도 저장하지도
 * 않는다 — 필드를 되살리면 규격에 없는 값을 기다리는 코드가 된다.
 *
 * <h3>★ {@code error} 는 객체가 아니라 문자열이다</h3>
 * <p>§2.7 의 실패 예시가 {@code "error": "추론 실패: ..."} 다. 구 규격의 {@code {code, message}}
 * 객체 형태를 그대로 두면 <b>실패 콜백이 전량 400</b> 이 되어 실패 사실 자체를 받지 못한다.
 *
 * <p>콜백 바디에 rawSn 도 창구 구분자도 없으므로, 위탁 시 발급한 {@code request_id} 로 대상 영상과
 * 창구를 역조회한다({@code WebhookIdempotencyLedger}). {@code request_id} 발급 게이트가 무단 콜백
 * 주입을 차단한다. 규격 §5.1 대로 <b>같은 request_id 의 콜백을 여러 번 받을 수 있으므로</b> 처리는
 * 멱등해야 하고, 요청 순서와 콜백 도착 순서는 일치하지 않을 수 있다.
 *
 * @param requestId 위탁 요청 시 발급한 식별자(=멱등키). 미발급 시 UNAUTHORIZED.
 * @param status    {@code completed|failed} 화이트리스트.
 * @param results   completed 시 분석 결과(서술). failed 시 무시(null 허용).
 * @param error     failed 시 오류 서술. completed 시 무시(null 허용).
 */
public record VlmResultRequest(

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                message = "request_id 는 영숫자/대시/언더스코어만 허용됩니다.")
        @JsonProperty("request_id")
        String requestId,

        @NotBlank
        @Pattern(regexp = "^(completed|failed)$",
                message = "status 는 completed|failed 중 하나여야 합니다.")
        String status,

        @Valid
        Results results,

        @Size(max = MAX_ERROR_LENGTH)
        String error
) {

    /** {@code error} 문자열 길이 상한 — 로그·기록 폭주 방어(CWE-770). */
    public static final int MAX_ERROR_LENGTH = 2000;

    /**
     * completed↔results 상호 조건 — completed 상태는 서술이 있어야 한다.
     *
     * <p>{@code results} 가 null 인 채 completed 로 오면 적재부에서 NPE(500)가 되므로 여기서 400 으로 막는다
     * (CWE-20 / fail-secure). {@code description} 자체의 공백·길이 검증은 {@link Results} 가 담당한다.
     */
    @JsonIgnore
    @AssertTrue(message = "completed 상태는 results(description) 가 필요합니다.")
    public boolean isResultsPresentWhenCompleted() {
        if (!"completed".equals(status)) return true;
        return results != null && results.description() != null && !results.description().isBlank();
    }

    /**
     * failed↔error 상호 조건 — failed 상태는 오류 서술이 있어야 한다.
     */
    @JsonIgnore
    @AssertTrue(message = "failed 상태는 error 가 필요합니다.")
    public boolean isErrorPresentWhenFailed() {
        if (!"failed".equals(status)) return true;
        return error != null && !error.isBlank();
    }

    /**
     * 분석 결과 — {@code results} 는 <b>단일 객체</b>이며 항목은 {@code description} 하나다(규격 §2.8).
     *
     * <p>서술은 <b>자연어 평문이고 형식이 고정돼 있지 않다</b>(§5.3) — 문자열 파싱에 의존하는 로직을
     * 두지 않는다.
     *
     * <p><b>알 수 없는 항목은 조용히 무시한다</b>({@code ignoreUnknown}). 판정 항목이 섞여 오거나
     * 벤더가 항목을 하나 더 붙였을 때 400 을 내면 그 결과는 <b>영영 유실</b>된다 — 규격 §5.1 상
     * 재전송은 3회로 끝나고 <b>결과를 다시 받을 수 있는 조회 API 가 없다</b>. 되받을 수 없는 입구에서
     * 엄격함은 손실로 직결되므로 여기서만 관대함을 택한다(요청 축의 엄격함과는 방향이 다르다).
     *
     * @param description 분석 결과 서술(≤2000자 — {@code LS_DATA_META.META_VL} 길이와 정확히 일치).
     *                    초과는 <b>자동 절단 없이</b> 400 이다(사일런트 손실 금지).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Results(
            @NotBlank(message = "description(분석 결과 서술)은 빈 값일 수 없습니다.")
            @Size(max = MAX_DESCRIPTION_LENGTH)
            String description
    ) {
        /** {@code description} 길이 상한 — {@code LS_DATA_META.META_VL} 컬럼 길이와 동일. */
        public static final int MAX_DESCRIPTION_LENGTH = 2000;
    }
}
