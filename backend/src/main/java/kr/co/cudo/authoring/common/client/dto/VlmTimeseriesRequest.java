package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 외부 VLM 서비스 위탁 요청 DTO — 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1) describe 규격 정합 (Phase 2).
 *
 * <p>{@code POST /v1/videovlm/describe} 요청 본문. 시간구간별 자연어 서술(시계열 메타) 추출을
 * 외부 VLM 서비스에 위탁한다. 결과는 {@code POST /v1/vlm/result} 콜백으로 비동기 수신한다.
 *
 * <h3>전송 JSON 스키마 (describe)</h3>
 * <pre>
 * {
 *   "request_id": "1f0d...-uuid",
 *   "media": {
 *     "type": "video",
 *     "source_type": "path",
 *     "path": "/data/videos/deid.mp4",
 *     "frame_policy": { "mode": "frame_interval", "framerate": 25 }
 *   },
 *   "callback_url": "http://저작도구/api/v1/vlm/result"
 * }
 * </pre>
 *
 * <p><b>상관관계</b>: 콜백 바디에는 rawSn 이 없다. 위탁 시 발급한 {@code request_id} 를
 * {@code WebhookIdempotencyLedger} 에 (request_id → rawSn) 매핑으로 등록해 두고, 콜백 수신부가
 * {@code resolveRawSn(request_id)} 로 역조회한다. rawSn/eventName/marks 는 describe 규격 밖이므로
 * 본 요청 바디에 포함하지 않는다(벤더 계약 준수).
 *
 * @param requestId   위탁 요청 식별자(=상관키). UUIDv4 로 발급(예측 불가). 콜백이 echo 로 되돌려 준다.
 * @param media       분석 대상 미디어 서술(type/source_type/path/frame_policy).
 * @param callbackUrl 결과 수신 webhook URL — 본 도구 고정 base URL + {@code /v1/vlm/result}(사용자 입력 미반영, SSRF 차단).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VlmTimeseriesRequest(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("media") Media media,
        @JsonProperty("callback_url") String callbackUrl
) {

    /** describe 미디어 서술 — 비식별 영상 경로 기반 path 소스. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Media(
            @JsonProperty("type") String type,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("path") String path,
            @JsonProperty("frame_policy") FramePolicy framePolicy
    ) {}

    /** describe 프레임 정책 — 기본 frame_interval + framerate. selected_frames 는 미사용 시 생략. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FramePolicy(
            @JsonProperty("mode") String mode,
            @JsonProperty("framerate") Integer framerate,
            @JsonProperty("selected_frames") List<Integer> selectedFrames
    ) {}

    /**
     * frame_interval 정책 describe 요청 팩토리 — 마킹/이벤트 미입력 규격(eventName/marks 미전송).
     *
     * @param requestId   상관키(UUIDv4).
     * @param path        비식별 영상 경로.
     * @param framerate   프레임 간격 정책의 framerate.
     * @param callbackUrl 결과 수신 콜백 URL(고정 base + /v1/vlm/result).
     */
    public static VlmTimeseriesRequest ofFrameInterval(
            String requestId, String path, int framerate, String callbackUrl) {
        return new VlmTimeseriesRequest(
                requestId,
                new Media("video", "path", path,
                        new FramePolicy("frame_interval", framerate, null)),
                callbackUrl);
    }
}
