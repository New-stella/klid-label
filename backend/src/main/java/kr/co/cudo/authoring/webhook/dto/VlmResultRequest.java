package kr.co.cudo.authoring.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 외부 VLM <b>verify</b>(이벤트 검증) 콜백 페이로드 — 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1) 정합.
 * [req: R3]
 *
 * <p>{@code POST /v1/vlm/callback} 요청 본문:
 * <pre>
 * // 성공
 * { "request_id": "00000001", "status": "completed",
 *   "results": { "accuracy": 0.8, "description": "한 남성이 전봇대 옆에서 쓰러진 상태로 확인됩니다." } }
 * // 실패
 * { "request_id": "00000001", "status": "failed",
 *   "error": { "code": "INFERENCE_ERROR", "message": "Video VLM inference failed" } }
 * </pre>
 *
 * <p><b>구 describe 규격의 {@code results:[{start_sec,end_sec,description}]} 배열은 폐기</b>됐다. 확정 계약이므로
 * 신·구 양쪽을 받아주는 관대한 파싱은 두지 않는다 — 무단 하위호환은 벤더 버그를 숨긴다. 과도기에 배열이 오면
 * Jackson 이 400 으로 거부하고 {@code VlmResultController} 가 <b>구조 힌트</b>만 로그로 남긴다.
 *
 * <p>콜백 바디에 rawSn 이 없으므로, 위탁 요청 시 발급한 {@code request_id} 로 rawSn 을 역조회한다
 * ({@code WebhookIdempotencyLedger}). {@code request_id} 발급 게이트가 무단 콜백 주입을 차단한다.
 *
 * @param requestId 위탁 요청 시 발급한 식별자(=멱등키). 미발급 시 UNAUTHORIZED.
 * @param status    {@code completed|failed} 화이트리스트.
 * @param results   completed 시 검증 결과(일치도 + 서술). failed 시 무시(null 허용).
 * @param error     failed 시 오류 정보. completed 시 무시(null 허용).
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

        @Valid
        VlmError error
) {

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
     * failed↔error 상호 조건 — failed 상태는 error 가 있어야 한다.
     */
    @JsonIgnore
    @AssertTrue(message = "failed 상태는 error 가 필요합니다.")
    public boolean isErrorPresentWhenFailed() {
        if (!"failed".equals(status)) return true;
        return error != null;
    }

    /**
     * verify 검증 결과 — {@code results} 는 <b>단일 객체</b>다(구 describe 구간 배열 폐기).
     *
     * <p><b>서버 검증이 유일한 방어선</b>이다: {@code accuracy} 는 검수큐(LS_DATA_META_REVIEW)를 거치지
     * 않고 화면으로만 나가므로(R12) 사람이 값을 걸러줄 지점이 없다. 범위·자릿수를 여기서 강제한다(CWE-20).
     *
     * @param accuracy    일치도 0~1(<b>경계 포함</b> — 0·1 은 정상값이라 거부하지 않는다). <b>optional</b> —
     *                    규격서가 필수 여부를 명시하지 않아 없으면 {@code vlm.accuracy} 행 자체를 만들지 않는다
     *                    (빈 문자열·placeholder 저장 금지). {@code NaN}/{@code Infinity} 는 Jackson 기본 설정
     *                    ({@code ALLOW_NON_NUMERIC_NUMBERS} 비활성)이 파싱 단계에서 거부한다.
     * @param description 검증 서술(≤2000자 — {@code LS_DATA_META.META_VL} 길이와 정확히 일치). 초과는
     *                    <b>자동 절단 없이</b> 400 이다(사일런트 손실 금지).
     */
    public record Results(
            @DecimalMin(value = "0.0", message = "accuracy 는 0 이상이어야 합니다.")
            @DecimalMax(value = "1.0", message = "accuracy 는 1 이하여야 합니다.")
            @Digits(integer = 1, fraction = MAX_ACCURACY_FRACTION_DIGITS,
                    message = "accuracy 자릿수가 허용 범위를 벗어났습니다.")
            BigDecimal accuracy,

            @NotBlank(message = "description(검증 서술)은 빈 값일 수 없습니다.")
            @Size(max = MAX_DESCRIPTION_LENGTH)
            String description
    ) {
        /**
         * {@code accuracy} 소수 자릿수 상한 — 값 범위(0~1)만으로는 자릿수가 무제한이라 문자열 적재 시
         * {@code META_VL}(2000자)를 넘길 수 있다. 상한을 두어 그 경로를 닫는다(CWE-20/770).
         */
        public static final int MAX_ACCURACY_FRACTION_DIGITS = 10;

        /** {@code description} 길이 상한 — {@code LS_DATA_META.META_VL} 컬럼 길이와 동일. */
        public static final int MAX_DESCRIPTION_LENGTH = 2000;
    }

    /**
     * verify 콜백 실패 오류 — failed 상태의 error 항목.
     *
     * @param code    벤더 오류 코드.
     * @param message 오류 메시지(로깅 시 개행 제거).
     */
    public record VlmError(
            @NotBlank
            @Size(max = 64)
            String code,

            @NotBlank
            @Size(max = 2000)
            String message
    ) {}
}
