package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NIA COCO 확장 {@code info} 블록 (xlsx v1.3).
 *
 * <p>파일 IO 없는 순수 매핑 산출물 — 값이 null 이어도 키는 유지된다({@link JsonInclude.Include#ALWAYS}).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NiaInfo(
        @JsonProperty("year") Integer year,
        @JsonProperty("version") String version,
        @JsonProperty("description") String description,
        @JsonProperty("date_created") String dateCreated
) {
}
