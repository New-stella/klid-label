package kr.co.cudo.authoring.notice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 공지 작성 요청 DTO.
 *
 * <p>보안: Entity 직접 바인딩 금지(Mass Assignment 방어) — 허용 필드만 정의.
 * 발행 상태/등록자/일시 등 서버 결정 필드는 받지 않는다. unknown 필드는 ignore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NoticeCreateRequest(
        @NotBlank(message = "제목은 필수입니다")
        @Size(max = 200, message = "제목은 200자 이하여야 합니다")
        String title,

        @NotBlank(message = "내용은 필수입니다")
        String content,

        boolean pinned
) {
}
