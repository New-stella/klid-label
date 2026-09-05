package kr.co.cudo.authoring.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;

/**
 * 개발/검수 환경 전용 테스트 토큰 발급 요청.
 *
 * <p>운영(prd) 환경에서는 상위 빈 자체가 등록되지 않아 endpoint 가 노출되지 않는다.
 *
 * <p>보안:
 * <ul>
 *   <li>{@code expSeconds} 60~86400(24h) 범위 강제 — DoS / 토큰 장기 유효 방지 (CWE-770).</li>
 *   <li>{@code role} 과 {@code channel} 일관성 검증은 Service 레이어에서 수행.</li>
 * </ul>
 */
@Schema(description = "[개발/검수 전용] 테스트 JWT 발급 요청")
public record DevTokenRequest(
        @Schema(description = "역할", example = "REVIEWER",
                allowableValues = {"ADMIN", "REVIEWER", "WORKER", "PORTAL_USER"}, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Role role,

        @Schema(description = "채널 — REVIEWER/WORKER 는 INTERNAL, PORTAL_USER 는 PORTAL 만 허용",
                example = "INTERNAL", allowableValues = {"INTERNAL", "PORTAL"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Channel channel,

        @Schema(description = "사용자 번호 (sub 클레임). null 이면 역할별 기본값 (REVIEWER=1001, WORKER=2001, PORTAL_USER=3001)",
                example = "1001")
        String userNo,

        @Schema(description = "이름 (name 클레임). null 이면 역할별 기본값", example = "검수자")
        String name,

        @Schema(description = "만료 시간(초). 60 ~ 86400(24h). null 이면 3600(1h)", example = "3600",
                minimum = "60", maximum = "86400")
        @Min(value = 60, message = "expSeconds 는 60 이상이어야 합니다.")
        @Max(value = 86400, message = "expSeconds 는 86400 이하여야 합니다.")
        Integer expSeconds
) {
}
