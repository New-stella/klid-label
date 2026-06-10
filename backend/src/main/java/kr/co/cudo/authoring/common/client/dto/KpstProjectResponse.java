package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KPST {@code POST /project} 응답 DTO.
 *
 * <p>규격: {@code { "result":"success", "prj_id":279 }} (22-deid-solution-api.md §22.3.3).
 * {@code prj_id} 는 성공 시에만 채워진다.
 */
public record KpstProjectResponse(
        String result,
        @JsonProperty("prj_id") Long prjId
) {
}
