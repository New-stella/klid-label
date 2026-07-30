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

    /**
     * <b>제출 완료(ACK 대기)</b> 상태 값 — Phase C-1 논블로킹 전환에서 신설.
     *
     * <p>{@code VlmTimeseriesStep} 은 더 이상 ACK 왕복을 기다리지 않으므로(스레드 미점유),
     * 스텝이 호출자에게 돌려줄 수 있는 사실은 "외부로 제출을 <b>개시</b>했다" 뿐이다.
     * 실제 수락({@code accepted}) 여부는 완료 핸들러가 {@code LS_BATCH_PROC_LOG.RESP_PAYLOAD_CN} 에
     * 비동기로 기록하며, 끝내 아무 신호도 없으면 미결 스위퍼가 회수한다.
     *
     * <p>이 값은 <b>외부 벤더 응답 값이 아니다</b>(벤더는 여전히 accepted 만 보낸다) — 내부 sentinel 이다.
     */
    public static final String STATUS_SUBMITTED = "submitted";

    /** 외부 위탁 비활성(enabled=false)/stub 모드에서 반환하는 sentinel. */
    public static VlmTimeseriesResponse skipped(String requestId) {
        return new VlmTimeseriesResponse(requestId, "skipped");
    }

    /** 논블로킹 제출 개시 sentinel — ACK 는 완료 핸들러가 비동기로 기록한다. */
    public static VlmTimeseriesResponse submitted(String requestId) {
        return new VlmTimeseriesResponse(requestId, STATUS_SUBMITTED);
    }
}
