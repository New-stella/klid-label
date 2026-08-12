package kr.co.cudo.authoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 단기 유효창 개시 요청 (R11).
 *
 * <p>보안:
 * <ul>
 *   <li>{@code adminPassword} 평문은 이 DTO 의 수명 동안만 메모리에 존재해야 하며
 *       <b>응답·로그·예외 메시지 어디에도</b> 실려서는 안 된다 (CWE-256/532).</li>
 *   <li>필드는 패스워드 하나뿐이다 — 유효기간·권한 등 <b>서버가 정해야 할 값을 바디로 받지 않는다</b>
 *       (CWE-915 Mass Assignment). 유효기간을 클라이언트가 정할 수 있으면 단기 유효창이라는 방어
 *       자체가 무의미해진다.</li>
 * </ul>
 */
@Schema(description = "관리자 단기 유효창 개시 요청 — 연동 서버 주소를 바꾸기 위한 짧은 인증 창을 연다.")
public record AdminSessionRequest(
        @Schema(description = "관리자 공유 패스워드 (BCrypt 비교)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "adminPassword 는 필수입니다.")
        @Size(min = 4, max = 100, message = "adminPassword 는 4~100자여야 합니다.")
        String adminPassword
) {
}
