package kr.co.cudo.authoring.evntanno.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;

import java.util.List;
import java.util.Map;

/**
 * event_annotation(외부 VLM VQA/CoT) payload DTO.
 *
 * <p><b>포맷 확정(위키 §24.3.1)</b> — Bean Validation 은 필수 키({@code event_class})만 강제하고
 * 나머지는 옵셔널이다. 알 수 없는 키는 무시({@code ignoreUnknown = true})하여 외부 스키마가 바뀌어도
 * 저장이 깨지지 않는다. 원문 무손실 보존이 목적이나, 인증 사용자에 의한 부분 DoS(CWE-770)를 막기 위해
 * 각 텍스트/리스트 필드에 합리적 상한({@code @Size})을 두고 중첩 후보는 {@code @Valid} 로 cascade 한다.
 *
 * <p>구조(위키 §24.3.1 — {@code caption}/{@code evidence} 는 후보 키 {@code c1..cn} 객체):
 * <ul>
 *   <li>{@code event_class} — 이벤트 분류(필수).</li>
 *   <li>{@code question} — VQA 질의.</li>
 *   <li>{@code caption} — 후보 객체{c1..cn}: caption_text + Chain-of-Thought 단계(cot).</li>
 *   <li>{@code answer} — 최종 답변.</li>
 *   <li>{@code evidence} — 후보 객체{c1..cn}: evidence_text + frame_id/obj_id/obj_bbox/obj_label.</li>
 * </ul>
 * caption cN 과 evidence cN 은 같은 키(cN)로 연결된다.
 *
 * <p>직렬화/역직렬화는 명시적 서브타입만 사용하며 Jackson 다형성 역직렬화
 * (enableDefaultTyping/@JsonTypeInfo) 는 사용하지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventAnnotationPayload(

        @NotBlank
        @Size(max = MAX_EVENT_CLASS)
        @JsonProperty("event_class")
        String eventClass,

        @Size(max = MAX_TEXT)
        @JsonProperty("question")
        String question,

        @Size(max = MAX_CANDIDATES)
        @JsonProperty("caption")
        Map<@Size(max = MAX_KEY) String, @Valid CaptionCandidate> caption,

        @Size(max = MAX_TEXT)
        @JsonProperty("answer")
        String answer,

        @Size(max = MAX_CANDIDATES)
        @JsonProperty("evidence")
        Map<@Size(max = MAX_KEY) String, @Valid EvidenceCandidate> evidence
) {

    /** 크기 상한(CWE-770 부분 DoS 방어) — 후보 키/텍스트/리스트 원소 수. */
    static final int MAX_EVENT_CLASS = 100;
    static final int MAX_TEXT = 4000;
    static final int MAX_COT_STEP = 2000;
    static final int MAX_KEY = 20;
    static final int MAX_CANDIDATES = 50;
    static final int MAX_COT_STEPS = 20;
    static final int MAX_LIST = 1000;
    static final int MAX_ID = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** caption 후보(c1..cn): caption_text + Chain-of-Thought 단계(cot, 1·2·3단계). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaptionCandidate(
            @Size(max = MAX_TEXT) @JsonProperty("caption_text") String captionText,
            @Size(max = MAX_COT_STEPS) @JsonProperty("cot") List<@Size(max = MAX_COT_STEP) String> cot
    ) {
    }

    /** evidence 후보(c1..cn): evidence_text + 프레임/객체 근거. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvidenceCandidate(
            @Size(max = MAX_TEXT) @JsonProperty("evidence_text") String evidenceText,
            @Size(max = MAX_LIST) @JsonProperty("frame_id") List<Integer> frameId,
            @Size(max = MAX_LIST) @JsonProperty("obj_id") List<@Size(max = MAX_ID) String> objId,
            @Size(max = MAX_LIST) @JsonProperty("obj_bbox") List<List<Double>> objBbox,
            @Size(max = MAX_LIST) @JsonProperty("obj_label") List<@Size(max = MAX_ID) String> objLabel
    ) {
    }

    /** payload 를 JSON 문자열로 직렬화. */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "event_annotation 직렬화에 실패했습니다.");
        }
    }

    /** JSON 문자열을 payload 로 역직렬화. 알 수 없는 키는 무시한다. */
    public static EventAnnotationPayload fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "event_annotation payload 는 필수입니다.");
        }
        try {
            return MAPPER.readValue(json, EventAnnotationPayload.class);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "event_annotation 역직렬화에 실패했습니다.");
        }
    }
}
