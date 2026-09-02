package kr.co.cudo.authoring.augment.integration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 「생성형 AI API 연동명세서 v1.3」 §4.1 {@code input_files[]} 항목.
 *
 * <p>파일 본문이 아니라 NAS 절대경로만 주고받는다(§5.3). 경로는 <b>비식별 프레임</b>만 허용한다.
 *
 * @param sequence 입력 순서(1부터, 중복 불가)
 * @param filePath 비식별 프레임 절대경로(1~500)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenAiInputFile(
        @JsonProperty("sequence") int sequence,
        @JsonProperty("file_path") String filePath) {
}
