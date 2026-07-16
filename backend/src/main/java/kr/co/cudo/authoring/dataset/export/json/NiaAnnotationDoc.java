package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 한 프레임의 자기완결 NIA COCO 확장 어노테이션 문서 (xlsx v1.3).
 *
 * <p>최상위 8키: info / dataset / licences / video / image / annotations / categories / type.
 * {@link JsonInclude.Include#ALWAYS} 로 값이 null 인 필수 키도 항상 직렬화된다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"info", "dataset", "licences", "video", "image", "annotations", "categories", "type"})
public record NiaAnnotationDoc(
        @JsonProperty("info") NiaInfo info,
        @JsonProperty("dataset") NiaDataset dataset,
        @JsonProperty("licences") List<NiaLicence> licences,
        @JsonProperty("video") NiaVideo video,
        @JsonProperty("image") NiaImage image,
        @JsonProperty("annotations") List<NiaAnnotation> annotations,
        @JsonProperty("categories") List<NiaCategory> categories,
        @JsonProperty("type") String type
) {
}
