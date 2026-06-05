package kr.co.cudo.authoring.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 문의(INQUIRY) 등록 요청.
 *
 * <p>보안 — 작성자(authorNo)/역할(authorRoleCd) 필드는 의도적으로 두지 않는다.
 * 인증 토큰(actor)에서만 도출하여 위변조를 차단한다 (시나리오 #1, CWE-915 Mass Assignment).
 *
 * <p>보안 — content 제어문자 가드(CWE-117 Log Injection / CWE-79 XSS 예방):
 * C0 제어문자(U+0000~U+001F) 중 줄바꿈·탭(\n \r \t)만 허용하고 나머지(NUL 등)는 400.
 * 정규식 {@link #NO_CONTROL_CHARS} 는 IssueCommentRequest 와 동일 정책을 공유한다.
 *
 * @param content 문의 본문 (필수, 1000자 이하, 제어문자 불가)
 * @param srcSn   프레임 단위 선택 참조 (선택)
 */
public record IssueCreateRequest(
        @NotBlank(message = "문의 내용은 필수입니다.")
        @Size(max = 1000, message = "문의 내용은 1000자 이하여야 합니다.")
        @Pattern(regexp = NO_CONTROL_CHARS, message = "문의 내용에 허용되지 않은 제어문자가 포함되어 있습니다.")
        String content,
        Long srcSn
) {

    /**
     * 허용 외 제어문자 차단 — \t(U+0009) \n(U+000A) \r(U+000D) 만 허용.
     * C0 제어문자(U+0000~U+001F) 외 U+007F(DEL) 도 차단 클래스에 포함한다.
     */
    public static final String NO_CONTROL_CHARS =
            "^[^\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]*$";
}
