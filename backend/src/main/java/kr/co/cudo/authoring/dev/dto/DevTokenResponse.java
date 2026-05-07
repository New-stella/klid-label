package kr.co.cudo.authoring.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 개발/검수 환경 전용 테스트 토큰 발급 응답.
 *
 * <p>보안:
 * <ul>
 *   <li>secret/key 절대 포함 금지.</li>
 *   <li>{@code claims} 는 디버깅 편의용 페이로드 사본 (서명 정보 X).</li>
 * </ul>
 */
@Schema(description = "[개발/검수 전용] 테스트 JWT 발급 응답")
public record DevTokenResponse(
        @Schema(description = "발급된 JWT 토큰 (HS256 서명)")
        String token,

        @Schema(description = "토큰 타입", example = "Bearer")
        String tokenType,

        @Schema(description = "만료 시각 (epoch millis)", example = "1747000000000")
        Long expiresAt,

        @Schema(description = "디코드된 페이로드 클레임 (디버깅 용도)")
        Map<String, Object> claims,

        @Schema(description = "Authorization 헤더 값 — 복사·붙여넣기 편의용",
                example = "Bearer eyJhbGciOiJIUzI1NiJ9...")
        String authorizationHeader
) {
}
