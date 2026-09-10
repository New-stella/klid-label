package kr.co.cudo.authoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관제 세션 갱신 중계 응답 (@design API-247).
 *
 * <p>관제 응답의 {@code data.session_token} · {@code data.refresh_token} 을 <b>가공 없이</b> 옮긴다.
 * refresh 토큰도 교체되므로 다음 갱신에는 새 값을 쓴다. 서버는 저장하지 않는다.
 * {@code toString} 은 토큰을 싣지 않는다(CWE-532).
 *
 * @param sessionToken 새 access 토큰 원문
 * @param refreshToken 새 refresh 토큰 원문
 */
public record ControlTokenRefreshResponse(
        @Schema(description = "새 access 토큰 원문 — 관제 응답 data.session_token")
        String sessionToken,
        @Schema(description = "새 refresh 토큰 원문 — 관제 응답 data.refresh_token. 요청에 쓴 refresh 토큰을 대체한다")
        String refreshToken) {

    @Override
    public String toString() {
        return "ControlTokenRefreshResponse[sessionToken=***, refreshToken=***]";
    }
}
