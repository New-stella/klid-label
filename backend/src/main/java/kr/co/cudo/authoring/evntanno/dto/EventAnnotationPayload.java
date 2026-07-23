package kr.co.cudo.authoring.evntanno.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;

import java.io.IOException;
import java.util.LinkedHashMap;
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

    /** 하위호환 배열 cot 관용 변환 시 순번 기반 단계 키 접미사(정본 샘플 "1단계"/"2단계"…). */
    static final String COT_STEP_SUFFIX = "단계";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * caption 후보(c1..cn): caption_text + Chain-of-Thought 단계(cot).
     *
     * <p>{@code cot} 은 정본 샘플과 정합하도록 <b>단계 라벨 키를 유지하는 객체</b>
     * ({@code {"1단계":..,"2단계":..,"3단계":..}})로 직렬화된다({@link LinkedHashMap} — 입력 순서 보존).
     *
     * <p><b>하위호환(HIGH)</b> — 과거 동결본은 {@code cot} 이 배열({@code List<String>})이었다. 저장된
     * 배열 cot 을 조회/재직렬화 경로({@link EventAnnotationPayload#fromJson})가 이 DTO 로 역직렬화할 때
     * 예외(500)가 나지 않도록 {@link CotDeserializer} 가 배열도 관용 흡수(순번 → {@code n단계} 키)한다.
     * 신규 저장분부터 객체 형태로 굳는다(배열 동결본 백필은 out of scope).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaptionCandidate(
            @Size(max = MAX_TEXT) @JsonProperty("caption_text") String captionText,
            @Size(max = MAX_COT_STEPS)
            @JsonProperty("cot")
            @JsonDeserialize(using = CotDeserializer.class)
            Map<@Size(max = MAX_KEY) String, @Size(max = MAX_COT_STEP) String> cot
    ) {
    }

    /**
     * cot 관용 역직렬화기(하위호환) — 객체·배열 두 형태를 모두 {@link LinkedHashMap} 으로 흡수한다.
     *
     * <ul>
     *   <li>객체({@code {"1단계":..}}) — 신규 정본 형태. 키·순서 보존.</li>
     *   <li>배열({@code ["..",".."]}) — 과거 동결본. 순번 1..n → {@code n단계} 키로 변환(500 방지).</li>
     *   <li>기타 스칼라 — 단일 {@code 1단계} 로 흡수(fail-secure).</li>
     * </ul>
     * 명시적 형태 분기만 사용하며 다형성 역직렬화(@JsonTypeInfo 등)는 쓰지 않는다.
     */
    static final class CotDeserializer extends JsonDeserializer<Map<String, String>> {
        @Override
        public Map<String, String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.readValueAsTree();
            if (node == null || node.isNull()) {
                return null;
            }
            Map<String, String> steps = new LinkedHashMap<>();
            if (node.isObject()) {
                node.fields().forEachRemaining(e ->
                        steps.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
            } else if (node.isArray()) {
                int i = 1;
                for (JsonNode v : node) {
                    steps.put(i + COT_STEP_SUFFIX, v.isNull() ? null : v.asText());
                    i++;
                }
            } else {
                steps.put(1 + COT_STEP_SUFFIX, node.asText());
            }
            return steps;
        }
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
