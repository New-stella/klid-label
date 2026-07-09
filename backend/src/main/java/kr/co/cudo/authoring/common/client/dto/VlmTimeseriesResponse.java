package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 외부 VLM describe 위탁 동기 응답 DTO — 벤더 확정 계약(v2.0.1) 정합 (Phase 2).
 *
 * <p>describe 는 비동기 위탁이므로 동기 응답은 <b>수락(Acknowledge)</b> 만 의미한다.
 * 실제 시계열 메타 결과는 {@code POST /v1/vlm/callback} 콜백으로 전달된다.
 *
 * <p>동기 응답 스키마: <pre>{ "request_id": "...", "status": "accepted" }</pre>
 * 구 규격의 {@code externalJobId} 는 describe 응답에 존재하지 않는다 — 상관관계는 {@code request_id} 로만 성립한다.
 *
 * <p>{@link JsonIgnoreProperties}{@code (ignoreUnknown=true)}: 벤더가 추가 필드를 보내도 역직렬화는
 * 실패하지 않으며 알려진 필드만 매핑한다. 값 검증(status="accepted" + request_id echo)은
 * {@code VlmClient} 에서 수행한다.
 *
 * @param requestId 위탁 요청과 동일해야 하는 상관키(echo). 불일치 시 EXTERNAL_API_ERROR.
 * @param status    수락 상태 — "accepted" 만 정상. 그 외/누락은 EXTERNAL_API_ERROR.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VlmTimeseriesResponse(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("status") String status
) {

    /** describe 수락 상태 값. */
    public static final String STATUS_ACCEPTED = "accepted";

    /** 외부 위탁 비활성(enabled=false)/stub 모드에서 반환하는 sentinel. */
    public static VlmTimeseriesResponse skipped(String requestId) {
        return new VlmTimeseriesResponse(requestId, "skipped");
    }
}
