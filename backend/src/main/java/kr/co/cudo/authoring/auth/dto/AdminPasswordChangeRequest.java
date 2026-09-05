package kr.co.cudo.authoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 패스워드 교체 요청. [@design API-223]
 *
 * <p>필드는 둘뿐이다 — <b>서버가 정해야 할 값을 바디로 받지 않는다</b>(CWE-915 Mass Assignment).
 * 특히 "무효화 여부"나 "적용 대상" 같은 것을 클라이언트가 고르게 하면, 교체하면서 자기 유효창만
 * 살려 두는 갈래가 생겨 무효화 규칙이 무너진다.
 *
 * <p>보안: 두 평문 모두 이 DTO 의 수명 동안만 메모리에 존재해야 하며 <b>응답·로그·예외 메시지
 * 어디에도</b> 실려서는 안 된다 (CWE-256/532).
 */
@Schema(description = "관리자 패스워드 교체 요청 — 현재 패스워드를 다시 확인한 뒤에만 바뀐다.")
public record AdminPasswordChangeRequest(

        @Schema(description = "현재 관리자 패스워드. 유효창이 열려 있어도 이 값을 다시 대조한다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "currentPassword 는 필수입니다.")
        @Size(min = 4, max = 100, message = "currentPassword 는 4~100자여야 합니다.")
        String currentPassword,

        @Schema(description = "새 관리자 패스워드. 현재 값과 같으면 거부한다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "newPassword 는 필수입니다.")
        @Size(min = 8, max = 100, message = "newPassword 는 8~100자여야 합니다.")
        String newPassword
) {
}
