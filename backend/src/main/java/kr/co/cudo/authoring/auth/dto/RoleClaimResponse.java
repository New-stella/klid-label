package kr.co.cudo.authoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 권한 자가 부여 응답 DTO — 새 토큰을 발급하여 FE 가 즉시 useAuthStore 를 갱신하도록 한다.
 *
 * <p>보안: 응답에는 새 access token, 부여된 role, 사용자 식별 정보만 포함한다.
 * adminPassword 평문이나 BCrypt 해시는 절대 포함되지 않는다.
 */
@Schema(description = "권한 자가 부여 응답 — 새 토큰 + 부여된 역할 + 사용자 정보")
public record RoleClaimResponse(
        @Schema(description = "새로 발급된 JWT (Bearer) — FE 는 이 값으로 기존 토큰을 즉시 교체")
        String accessToken,

        @Schema(description = "부여된 역할", example = "WORKER")
        String role,

        @Schema(description = "사용자 번호", example = "1001")
        Long userNo,

        @Schema(description = "사용자 이름", example = "검수자A")
        String userName
) {
}
