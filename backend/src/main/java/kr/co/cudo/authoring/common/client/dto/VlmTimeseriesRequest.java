package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 외부 시계열 분석 위탁 요청 DTO — 확정 계약(KLID 연동 API v1.1.0) 정합.
 *
 * <p>묘사({@code POST /v1/videovlm-klid/describe})와 추가 질문
 * ({@code POST /v1/videovlm-klid/describe-sub}) 두 창구가 <b>같은 요청 형식</b>을 쓴다(§3.2·§3.3).
 * 결과는 {@code POST /v1/vlm/callback} 콜백으로 비동기 수신한다.
 *
 * <h3>전송 JSON 스키마 (§2.3~§2.5)</h3>
 * <pre>
 * {
 *   "request_id": "1f0d...-uuid",
 *   "event_type": "fall",
 *   "media": {
 *     "type": "video",
 *     "source_type": "path",
 *     "path": "/data/videos/deid.mp4",
 *     "frame_policy": { "mode": "frame_selected", "selected_frames": [0, 10, 13] }
 *   },
 *   "callback_url": "http://저작도구/api/v1/vlm/callback"
 * }
 * </pre>
 *
 * <h3>★ {@code framerate} 는 싣지 않는다 (구 verify 규격에서 폐기)</h3>
 * <p>규격 §2.5 는 <b>프레임 선택 방식(mode)만 연동 시스템이 지정하고 간격·장수 등 세부 값은
 * 서버가 관리한다</b>고 못 박는다. {@code frame_policy} 에 {@code framerate} 필드 자체가 없다.
 * 구 규격의 "추출 간격을 우리가 지정한다" 는 폐기됐다 — 되살리면 정의되지 않은 필드가 실린다.
 *
 * <p><b>상관관계</b>: 콜백 바디에는 rawSn 도 창구 구분자도 없다. 위탁 시 발급한 {@code request_id} 를
 * {@code WebhookIdempotencyLedger} 에 (request_id → 채널, rawSn) 매핑으로 등록해 두고, 콜백 수신부가
 * 그 채널로 어느 창구의 결과인지 역조회한다. 두 창구는 <b>각각 별개의 request_id</b> 로 나가야 한다 —
 * 같은 값을 쓰면 역조회가 한쪽을 덮어 결과가 유실된다(규격 §5.2 도 동일 request_id 중복 요청을
 * 별개 작업으로 처리하며 중복 방지는 연동 시스템 책임이라고 명시한다).
 *
 * @param requestId   위탁 요청 식별자(=상관키). UUIDv4 로 발급(예측 불가). 콜백이 echo 로 되돌려 준다.
 * @param eventType   분석 대상 이벤트 유형. 조달처는 관제 인입값
 *                    {@code LS_DATA_INGEST.VRFC_EVNT_TYPE_CD} 이며, 수용 여부는 벤더 응답이 정한다
 *                    (우리 쪽 허용목록으로 사전 차단하지 않는다).
 * @param media       분석 대상 미디어 서술(type/source_type/path/frame_policy).
 * @param callbackUrl 결과 수신 webhook URL — 본 도구 고정 base URL + {@code /v1/vlm/callback}(사용자 입력 미반영, SSRF 차단).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VlmTimeseriesRequest(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("media") Media media,
        @JsonProperty("callback_url") String callbackUrl,
        @JsonProperty("prompt") String prompt
) {

    /** 미디어 서술 — 비식별 영상 경로 기반 path 소스(§2.4). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Media(
            @JsonProperty("type") String type,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("path") String path,
            @JsonProperty("frame_policy") FramePolicy framePolicy
    ) {}

    /**
     * 프레임 선택 정책 (§2.5) — <b>mode 와 selected_frames 만</b> 싣는다.
     *
     * <ul>
     *   <li>{@code mode} — {@code frame_interval} | {@code uniform} | {@code frame_selected} (필수).
     *       정의되지 않은 mode 는 400 이다.</li>
     *   <li>{@code selected_frames} — {@code frame_selected} 일 때만 존재한다. 정수 배열,
     *       <b>최대 {@value #MAX_SELECTED_FRAMES}개</b>, 음수 불가. 초과하면 400 이다.</li>
     * </ul>
     *
     * <p>{@code framerate} 필드는 <b>규격에 존재하지 않는다</b> — 추가하지 말 것.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FramePolicy(
            @JsonProperty("mode") String mode,
            @JsonProperty("selected_frames") List<Integer> selectedFrames
    ) {}

    /** frame_policy mode — 일정 프레임 간격 추출(간격값은 서버가 관리). */
    public static final String MODE_FRAME_INTERVAL = "frame_interval";

    /** frame_policy mode — 지정 프레임만 사용. */
    public static final String MODE_FRAME_SELECTED = "frame_selected";

    /**
     * {@code selected_frames} 개수 상한 — 규격 §2.5·§2.9. 초과하면 벤더가 400 으로 거부한다.
     *
     * <p>구 verify 규격의 상한 8 은 폐기됐다.
     */
    public static final int MAX_SELECTED_FRAMES = 600;

    /**
     * {@code frame_interval} 정책 요청 팩토리 — 실을 프레임 인덱스가 하나도 없을 때의 폴백.
     *
     * <p>빈 {@code selected_frames} 를 보내면 규격 위반이므로, 마킹이 없거나 마킹 본문에서
     * 유효한 프레임을 하나도 얻지 못한 경우 이 모드로 내려 서버가 간격을 정하게 한다.
     *
     * @param requestId   상관키(UUIDv4).
     * @param eventType   분석 대상 이벤트 유형(조달값 그대로).
     * @param path        비식별 영상 경로.
     * @param callbackUrl 결과 수신 콜백 URL(고정 base + /v1/vlm/callback).
     */
    public static VlmTimeseriesRequest ofFrameInterval(
            String requestId, String eventType, String path, String callbackUrl) {
        return new VlmTimeseriesRequest(
                requestId, eventType,
                new Media("video", "path", path,
                        new FramePolicy(MODE_FRAME_INTERVAL, null)),
                callbackUrl, null);
    }

    /**
     * {@code frame_selected} 정책 요청 팩토리 — 기본 경로.
     *
     * <p>{@code selectedFrames} 는 호출자가 <b>정렬·중복제거·음수 제거·상한
     * {@value #MAX_SELECTED_FRAMES} 적용</b>을 끝낸 값이어야 한다(정책 판정을 DTO 로 분산시키지
     * 않는다). 방어적으로 불변 복사만 수행한다.
     *
     * @param selectedFrames 분석 대상 프레임 인덱스 목록. 마킹이 있으면 작업자가 마킹한 프레임,
     *                       없으면 자동으로 고른 프레임이다.
     */
    public static VlmTimeseriesRequest ofFrameSelected(
            String requestId, String eventType, String path,
            List<Integer> selectedFrames, String callbackUrl) {
        return new VlmTimeseriesRequest(
                requestId, eventType,
                new Media("video", "path", path,
                        new FramePolicy(MODE_FRAME_SELECTED, List.copyOf(selectedFrames))),
                callbackUrl, null);
    }

    /**
     * <b>추가 질문 축</b> 요청으로 바꾼다 — 창구가 {@code custom} 으로 교체되면서 생긴 변환.
     *
     * <p>두 축은 {@code request_id} 를 빼면 <b>미디어·프레임 정책·콜백 주소가 같다</b>(규격 §3.4 —
     * "그 외 요청 형식은 나머지 세 API 와 같다"). 그래서 묘사 축 요청에서 <b>두 가지만 바꾼다</b>:
     *
     * <ul>
     *   <li><b>{@code event_type} 을 싣지 않는다</b> — 이 창구는 그 필드를 쓰지 않는다. 그 덕에 추가
     *       질문 축에서는 <b>이벤트 유형 미수신·미지원으로 인한 4xx 가 구조적으로 사라진다</b>
     *       (묘사 축에는 그대로 남는다).</li>
     *   <li><b>{@code prompt} 에 질문 문구를 직접 싣는다</b> — 최대 {@value #MAX_PROMPT_LENGTH}자.
     *       그 문구는 저작도구가 검증 이벤트 유형별로 보관하는 값이며, <b>위탁 시점에 조달</b>해
     *       원장에 보관하고 결과 수신 시 재조달하지 않는다.</li>
     * </ul>
     *
     * <p>⚠ <b>{@code request_id} 는 반드시 달라야 한다</b> — 콜백 바디에 창구 구분자가 없어 원장 채널로
     * 역조회하므로, 같은 값을 쓰면 한쪽 결과가 유실된다.
     *
     * @param base       묘사 축 요청(미디어·프레임 정책·콜백 주소의 원본)
     * @param requestId  추가 질문 축 전용 상관키 — 묘사 축과 <b>다른</b> 값
     * @param prompt     보낼 질문 문구. <b>비어 있으면 이 축을 위탁하지 않는다</b>(호출자가 판단)
     */
    public static VlmTimeseriesRequest toCustom(VlmTimeseriesRequest base, String requestId, String prompt) {
        return new VlmTimeseriesRequest(requestId, null, base.media(), base.callbackUrl(), prompt);
    }

    /**
     * {@code prompt} 길이 상한 — 규격 §2.4. 초과하면 벤더가 400 으로 거부한다.
     *
     * <p>같은 값이 질문 보관 칸의 폭이기도 하다 — 우리가 보관할 수 없는 길이는 애초에 보낼 수 없다.
     */
    public static final int MAX_PROMPT_LENGTH = 4000;
}
