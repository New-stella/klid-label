package kr.co.cudo.authoring.notice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 공지 수정 요청 DTO.
 *
 * <p>보안: Entity 직접 바인딩 금지(Mass Assignment 방어). unknown 필드는 ignore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NoticeUpdateRequest(
        @NotBlank(message = "제목은 필수입니다")
        @Size(max = 200, message = "제목은 200자 이하여야 합니다")
        String title,

        @NotBlank(message = "내용은 필수입니다")
        String content,

        boolean pinned
) {
}
