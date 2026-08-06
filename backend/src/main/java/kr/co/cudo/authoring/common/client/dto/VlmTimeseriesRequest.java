package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 외부 VLM 서비스 위탁 요청 DTO — 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1) <b>verify</b> 규격 정합.
 *
 * <p>{@code POST /v1/videovlm/verify} 요청 본문. 지정한 {@code event_type} 에 대한 시계열 분석을
 * 외부 VLM 서비스에 위탁한다. 결과는 {@code POST /v1/vlm/callback} 콜백으로 비동기 수신한다.
 *
 * <h3>전송 JSON 스키마 (verify §2.1)</h3>
 * <pre>
 * {
 *   "request_id": "1f0d...-uuid",
 *   "event_type": "fall",
 *   "media": {
 *     "type": "video",
 *     "source_type": "path",
 *     "path": "/data/videos/deid.mp4",
 *     "frame_policy": { "mode": "frame_interval", "framerate": 25 }
 *   },
 *   "callback_url": "http://저작도구/api/v1/vlm/callback"
 * }
 * </pre>
 * <p>{@code frame_selected} 인 경우 frame_policy 는
 * {@code { "mode": "frame_selected", "framerate": 25, "selected_frames": [0, 10, 13] }} 이다.
 *
 * <p><b>상관관계</b>: 콜백 바디에는 rawSn 이 없다. 위탁 시 발급한 {@code request_id} 를
 * {@code WebhookIdempotencyLedger} 에 (request_id → rawSn) 매핑으로 등록해 두고, 콜백 수신부가
 * {@code resolveRawSn(request_id)} 로 역조회한다. rawSn/eventName/marks 는 벤더 규격 밖이므로
 * 본 요청 바디에 포함하지 않는다 — 마킹 정보는 {@code frame_policy} 로만 반영된다.
 *
 * @param requestId   위탁 요청 식별자(=상관키). UUIDv4 로 발급(예측 불가). 콜백이 echo 로 되돌려 준다.
 * @param eventType   검증 대상 이벤트 유형(벤더 §3.3 6종). 조달처는 관제 인입값
 *                    {@code LS_DATA_INGEST.VRFC_EVNT_TYPE_CD} 이며, 허용목록 판정은 위탁 단계가 수행한다.
 * @param media       분석 대상 미디어 서술(type/source_type/path/frame_policy).
 * @param callbackUrl 결과 수신 webhook URL — 본 도구 고정 base URL + {@code /v1/vlm/callback}(사용자 입력 미반영, SSRF 차단).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VlmTimeseriesRequest(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("media") Media media,
        @JsonProperty("callback_url") String callbackUrl
) {

    /** verify 미디어 서술 — 비식별 영상 경로 기반 path 소스. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Media(
            @JsonProperty("type") String type,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("path") String path,
            @JsonProperty("frame_policy") FramePolicy framePolicy
    ) {}

    /**
     * verify 프레임 정책 (벤더 §3.2).
     *
     * <ul>
     *   <li>{@code mode} — {@code frame_interval} | {@code frame_selected} (필수)</li>
     *   <li>{@code framerate} — <b>mode 무관 필수</b>. "초당 프레임수"가 아니라 <b>몇 프레임당 1장을
     *       뽑을지</b>(추출 간격)를 뜻한다(§2.1 본문). 따라서 자동 마킹의 프레임 간격
     *       ({@code LS_MARKING.FRME_INTV_NOCS})이 의미상 대응값이다.</li>
     *   <li>{@code selected_frames} — {@code frame_selected} 일 때만 존재하며 <b>최대 8개</b>.</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FramePolicy(
            @JsonProperty("mode") String mode,
            @JsonProperty("framerate") Integer framerate,
            @JsonProperty("selected_frames") List<Integer> selectedFrames
    ) {}

    /** frame_policy mode — 프레임 간격 추출. */
    public static final String MODE_FRAME_INTERVAL = "frame_interval";

    /** frame_policy mode — 지정 프레임 추출. */
    public static final String MODE_FRAME_SELECTED = "frame_selected";

    /**
     * {@code frame_interval} 정책 verify 요청 팩토리 — 자동 마킹·마킹 부재 경로.
     *
     * @param requestId   상관키(UUIDv4).
     * @param eventType   검증 대상 이벤트 유형(허용목록 판정 완료값).
     * @param path        비식별 영상 경로.
     * @param framerate   추출 간격(몇 프레임당 1장). 반드시 {@code > 0} 이어야 한다(0 이면 벤더 422).
     * @param callbackUrl 결과 수신 콜백 URL(고정 base + /v1/vlm/callback).
     */
    public static VlmTimeseriesRequest ofFrameInterval(
            String requestId, String eventType, String path, int framerate, String callbackUrl) {
        return new VlmTimeseriesRequest(
                requestId, eventType,
                new Media("video", "path", path,
                        new FramePolicy(MODE_FRAME_INTERVAL, framerate, null)),
                callbackUrl);
    }

    /**
     * {@code frame_selected} 정책 verify 요청 팩토리 — 수동 마킹 경로.
     *
     * <p>{@code selectedFrames} 는 호출자가 <b>정렬·중복제거·상한 8 적용</b>을 끝낸 값이어야 한다
     * (정책 판정을 DTO 로 분산시키지 않는다). 방어적으로 불변 복사만 수행한다.
     *
     * @param framerate      {@code frame_selected} 에서도 <b>필수</b>다(벤더 §3.2).
     * @param selectedFrames 분석 대상 프레임 인덱스 목록.
     */
    public static VlmTimeseriesRequest ofFrameSelected(
            String requestId, String eventType, String path, int framerate,
            List<Integer> selectedFrames, String callbackUrl) {
        return new VlmTimeseriesRequest(
                requestId, eventType,
                new Media("video", "path", path,
                        new FramePolicy(MODE_FRAME_SELECTED, framerate, List.copyOf(selectedFrames))),
                callbackUrl);
    }
}
