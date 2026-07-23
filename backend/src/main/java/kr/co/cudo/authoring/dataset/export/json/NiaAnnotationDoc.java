package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 한 프레임의 자기완결 NIA COCO 확장 어노테이션 문서 (xlsx v1.3).
 *
 * <p>최상위 9키: info / dataset / licences / video / event / image / annotations /
 * categories / type. {@link JsonInclude.Include#ALWAYS} 로 값이 null 인 필수 키도 항상 직렬화된다.
 *
 * <p>{@code event}(위키 §24.3.1, 정본 샘플 키) — 검수 승인 시점에 동결된 event_annotation payload
 * 원문(후보 키 {@code c1..cn} 객체: caption/evidence, obj_id 문자열 배열)을 그대로 pass-through 한다.
 * JSON 출력 키만 {@code event} 이며(정본 샘플 정합), 내부 필드/클래스명은 {@code eventAnnotation}
 * 을 유지한다. 위치는 {@code video} 다음(현행 순서)으로 고정한다. {@link JsonNode} 로 보관해 키
 * 순서·형태를 보존하며(재직렬화 편차 없음), 프레임마다 동일 내용을 video 블록처럼 자기완결로
 * 포함한다. 동결 event 가 없으면 {@code "event": null} 로 직렬화된다(클래스 {@code ALWAYS} 정책 —
 * 키는 항상 present, 값만 null).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"info", "dataset", "licences", "video", "event",
        "image", "annotations", "categories", "type"})
public record NiaAnnotationDoc(
        @JsonProperty("info") NiaInfo info,
        @JsonProperty("dataset") NiaDataset dataset,
        @JsonProperty("licences") List<NiaLicence> licences,
        @JsonProperty("video") NiaVideo video,
        @JsonProperty("event") JsonNode eventAnnotation,
        @JsonProperty("image") NiaImage image,
        @JsonProperty("annotations") List<NiaAnnotation> annotations,
        @JsonProperty("categories") List<NiaCategory> categories,
        @JsonProperty("type") String type
) {
}
