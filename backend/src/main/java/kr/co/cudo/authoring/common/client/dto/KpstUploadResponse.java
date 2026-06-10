package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * KPST {@code POST /upload} 응답 DTO.
 *
 * <p>규격: {@code { "result":"success", "data":{ "inputPath":"...", "files":[...], "count":n } }}
 * (22-deid-solution-api.md §22.3.2)
 *
 * <p>{@code data.inputPath} 는 끝에 {@code /} 를 포함하며 {@code /project} 요청의 {@code input_path}
 * 로 그대로 전달한다.
 */
public record KpstUploadResponse(
        String result,
        Data data
) {
    public record Data(
            @JsonProperty("inputPath") String inputPath,
            List<String> files,
            int count
    ) {
    }
}
