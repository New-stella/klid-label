package kr.co.cudo.authoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관제 세션 갱신 중계 요청 (@design API-247).
 *
 * <p>refresh 토큰 외의 입력을 받지 않는다 — 요청이 호출 대상 주소·경로를 바꿀 수단이 없어야 한다.
 * {@code toString} 은 토큰을 싣지 않는다(CWE-532).
 *
 * @param refreshToken 관제 refresh 토큰 원문 — 관제 갱신 호출의 유일한 자격증명이며 유효성 판정은 관제가 한다
 */
public record ControlTokenRefreshRequest(
        @Schema(description = "관제 refresh 토큰 원문. 필수 · 공백 불가 · 최대 4096자. 서버는 저장하거나 로그에 남기지 않는다.",
                requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 4096)
        @NotBlank(message = "refreshToken 은 필수입니다.")
        @Size(max = 4096, message = "refreshToken 은 4096자 이하여야 합니다.")
        String refreshToken) {

    @Override
    public String toString() {
        return "ControlTokenRefreshRequest[refreshToken=***]";
    }
}
