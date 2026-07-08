package kr.co.cudo.authoring.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 외부 VLM describe 콜백 페이로드 — 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1) 정합.
 *
 * <p>{@code POST /v1/vlm/result} 요청 본문. 콜백 규격(docs/v2-wiki/09-vlm-timeseries.md §9.5/§9.6):
 * <pre>
 * // 성공
 * { "request_id": "...", "status": "completed",
 *   "results": [ {"start_sec":0,"end_sec":8,"description":"..."}, ... ] }
 * // 실패
 * { "request_id": "...", "status": "failed",
 *   "error": {"code":"...","message":"..."} }
 * </pre>
 *
 * <p>콜백 바디에 rawSn 이 없으므로, 위탁 요청 시 발급한 {@code request_id} 로 rawSn 을 역조회한다
 * ({@code WebhookIdempotencyLedger.resolveRawSn}). {@code request_id} 발급 게이트가 무단 콜백 주입을 차단한다.
 *
 * @param requestId 위탁 요청 시 발급한 식별자(=멱등키). 미발급 시 UNAUTHORIZED.
 * @param status    {@code completed|failed} 화이트리스트.
 * @param results   completed 시 시간구간별 시계열 서술. failed 시 무시(null 허용).
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

        @Size(max = 500, message = "results 는 최대 500개까지만 허용됩니다.")
        @Valid
        List<Segment> results,

        @Valid
        VlmError error
) {

    /**
     * completed↔results 상호 조건 — completed 상태는 results 가 최소 1건 있어야 한다.
     */
    @JsonIgnore
    @AssertTrue(message = "completed 상태는 results 가 최소 1건 필요합니다.")
    public boolean isResultsPresentWhenCompleted() {
        if (!"completed".equals(status)) return true;
        return results != null && !results.isEmpty();
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
     * 시간구간별 시계열 서술 — describe 콜백 results 항목.
     *
     * @param startSec    구간 시작 초(0 ≤ startSec ≤ endSec ≤ {@link #MAX_SEC}).
     * @param endSec      구간 종료 초(startSec ≤ endSec ≤ {@link #MAX_SEC}).
     * @param description 해당 구간의 자연어 서술(≤2000자). {@code LS_DATA_META.META_VL} 에 적재.
     */
    public record Segment(
            @NotNull
            @Min(value = 0, message = "start_sec 는 0 이상이어야 합니다.")
            @Max(value = MAX_SEC, message = "start_sec 는 " + MAX_SEC + " 이하여야 합니다.")
            @JsonProperty("start_sec")
            Integer startSec,

            @NotNull
            @Min(value = 0, message = "end_sec 는 0 이상이어야 합니다.")
            @Max(value = MAX_SEC, message = "end_sec 는 " + MAX_SEC + " 이하여야 합니다.")
            @JsonProperty("end_sec")
            Integer endSec,

            @NotBlank(message = "description(자연어 서술)은 빈 값일 수 없습니다.")
            @Size(max = 2000)
            String description
    ) {
        /** 구간 초 상한 — 24시간(초). 역전/과대 구간으로 인한 자원·데이터 오염 차단(CWE-20). */
        public static final long MAX_SEC = 86_400L;

        /** META_KEY 규격 — "{start_sec}-{end_sec}". */
        public String metaKey() {
            return startSec + "-" + endSec;
        }

        /**
         * 구간 정합 — end_sec 는 start_sec 이상이어야 한다(역전 구간 차단, CWE-20).
         * null 은 {@code @NotNull} 이 별도로 잡으므로 여기서는 통과 처리한다.
         */
        @JsonIgnore
        @AssertTrue(message = "end_sec 는 start_sec 이상이어야 합니다.")
        public boolean isRangeOrdered() {
            if (startSec == null || endSec == null) return true;
            return endSec >= startSec;
        }
    }

    /**
     * describe 콜백 실패 오류 — failed 상태의 error 항목.
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
