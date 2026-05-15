package kr.co.cudo.authoring.label.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 객체별 속성값 일괄 upsert 요청 DTO (CVAT-Like 라벨 풀 포팅 Phase 3).
 *
 * <p>body 예시:
 * <pre>
 * { "values": [ {"attrId": 11, "value": "yes"}, {"attrId": 12, "value": "N"} ] }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LabelAttrValueUpsertRequest(
        @NotNull(message = "values 는 필수입니다.")
        @Valid
        List<Entry> values
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            @NotNull(message = "attrId 는 필수입니다.")
            Long attrId,

            @Size(max = 1000, message = "value 는 1000자 이하여야 합니다.")
            String value
    ) {
    }
}
