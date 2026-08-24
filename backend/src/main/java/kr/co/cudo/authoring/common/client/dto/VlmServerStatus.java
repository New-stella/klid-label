package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 외부 시계열 분석 서버 상태 조회 응답 — 확정 계약(KLID 연동 API v1.1.0) §3.5.
 *
 * <p>{@code GET /v1/videovlm-klid/status} 응답:
 * <pre>{ "status": "ready", "queue": 0, "pending": 0 }</pre>
 *
 * <p>요청을 보내기 전에 서버가 처리 가능한 상태인지 확인하는 용도다.
 *
 * @param status  {@link #READY} 즉시 접수 가능 · {@link #BUSY} 접수는 되나 결과 지연 ·
 *                {@link #LOADING} 준비 중이라 아직 처리 불가.
 * @param queue   처리 대기 중인 작업 수(다른 경로의 요청 포함).
 * @param pending 접수 후 콜백 전송 전인 요청 수.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VlmServerStatus(
        @JsonProperty("status") String status,
        @JsonProperty("queue") Integer queue,
        @JsonProperty("pending") Integer pending
) {

    /** 즉시 접수하여 처리할 수 있다. */
    public static final String READY = "ready";

    /** 처리 중이거나 대기가 있다 — 접수는 되지만 결과가 지연될 수 있다. */
    public static final String BUSY = "busy";

    /** 서버가 준비 중이므로 아직 처리할 수 없다. */
    public static final String LOADING = "loading";

    /**
     * 위탁을 보내도 되는 상태인가.
     *
     * <p>{@link #LOADING} 만 거부한다 — {@link #BUSY} 는 규격상 <b>접수는 되고</b> 결과만 지연되므로
     * 막으면 정상 위탁을 우리가 먼저 차단하게 된다. 상태를 해석하지 못한 경우(null·미지의 값)도
     * 통과시킨다 — 사본 목록으로 벤더 값을 좁히지 않는다는 이 프로젝트의 원칙과 같은 취지이며,
     * 실제 수용 여부는 위탁 응답이 정한다.
     */
    public boolean isSubmittable() {
        return !LOADING.equals(status);
    }
}
