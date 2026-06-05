package kr.co.cudo.authoring.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 이슈 댓글 작성 요청.
 *
 * <p>보안 — 작성자/역할 필드는 두지 않는다(토큰에서만 도출, 시나리오 #1 Mass Assignment 방어).
 *
 * <p>보안 — content 제어문자 가드(CWE-117 Log Injection / CWE-79 XSS 예방):
 * IssueCreateRequest 와 동일하게 줄바꿈·탭만 허용하고 나머지 C0 제어문자는 400.
 *
 * @param content 댓글 본문 (필수, 1000자 이하, 제어문자 불가)
 */
public record IssueCommentRequest(
        @NotBlank(message = "댓글 내용은 필수입니다.")
        @Size(max = 1000, message = "댓글 내용은 1000자 이하여야 합니다.")
        @Pattern(regexp = IssueCreateRequest.NO_CONTROL_CHARS,
                message = "댓글 내용에 허용되지 않은 제어문자가 포함되어 있습니다.")
        String content
) {
}
