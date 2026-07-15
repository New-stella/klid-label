package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NIA COCO 확장 {@code licences} 항목 (xlsx v1.3, British 철자 유지).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NiaLicence(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("url") String url
) {

    /** 기본 라이선스 1건 (id=1, "Private Use"). */
    public static NiaLicence privateUse() {
        return new NiaLicence(1, "Private Use", "");
    }
}
