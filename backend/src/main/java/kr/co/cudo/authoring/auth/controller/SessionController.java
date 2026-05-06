package kr.co.cudo.authoring.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.cudo.authoring.auth.dto.MeResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.JwtAuthenticationFilter;
import kr.co.cudo.authoring.common.security.TokenClaims;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Session", description = "현재 사용자 세션 정보 — 관제/포털 채널의 JWT 토큰을 검증하여 sub/role/channel 반환.")
@RestController
@RequestMapping("/v1")
public class SessionController {

    @Operation(
            summary = "현재 인증된 사용자 정보 조회",
            description = "JWT 클레임에서 sub(사용자 ID), role(REVIEWER/WORKER/PORTAL_USER), channel(CONTROL/PORTAL)을 추출하여 반환한다."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "토큰 없음/만료/검증 실패")
    })
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TokenClaims claims)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Object nameAttr = request.getAttribute(JwtAuthenticationFilter.AUTH_NAME_ATTR);
        String name = nameAttr instanceof String s ? s : null;
        String role = claims.role() == null ? null : claims.role().name();
        String channel = claims.channel() == null ? null : claims.channel().name();
        return ApiResponse.ok(new MeResponse(claims.sub(), name, role, channel));
    }
}
