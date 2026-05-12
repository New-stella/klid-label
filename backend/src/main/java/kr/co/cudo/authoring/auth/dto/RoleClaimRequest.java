package kr.co.cudo.authoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.security.Role;

/**
 * 권한 자가 부여 요청 DTO — 인증은 되었으나 role 클레임이 없는 사용자가
 * 관리자 패스워드와 함께 본인에게 WORKER/REVIEWER 역할을 부여한다.
 *
 * <p>보안:
 * <ul>
 *   <li>{@code adminPassword} 평문은 본 DTO 의 LIFE-CYCLE 동안만 메모리에 존재해야 하며
 *       절대 로그/응답/예외 메시지에 노출되어서는 안 된다 (CWE-256/532).</li>
 *   <li>{@code role} 은 enum 타입 자체로 화이트리스트가 강제됨. {@code PORTAL_USER} 는
 *       별도 채널(포털 서버)에서만 발급되므로 본 API 에서는 서비스 단에서 거절한다.</li>
 * </ul>
 */
@Schema(description = "권한 자가 부여 요청 — 인증된 사용자가 관리자 패스워드와 함께 역할(WORKER/REVIEWER)을 부여한다.")
public record RoleClaimRequest(
        @Schema(description = "부여할 역할 (WORKER 또는 REVIEWER 만 허용. PORTAL_USER 는 별도 채널)",
                example = "WORKER", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "role 은 필수입니다.")
        Role role,

        @Schema(description = "관리자 공유 패스워드 (BCrypt 비교)",
                example = "admin1234", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "adminPassword 는 필수입니다.")
        @Size(min = 4, max = 100, message = "adminPassword 는 4~100자여야 합니다.")
        String adminPassword
) {
}
