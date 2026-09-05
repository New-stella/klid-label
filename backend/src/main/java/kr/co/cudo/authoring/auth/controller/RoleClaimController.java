package kr.co.cudo.authoring.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.auth.dto.RoleClaimRequest;
import kr.co.cudo.authoring.auth.dto.RoleClaimResponse;
import kr.co.cudo.authoring.auth.service.RoleClaimService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 부트스트랩 컨트롤러 (역할 자가 부여).
 *
 * <p>내부 채널로 인증한 사용자가 관리자 공유 패스워드와 함께 본인에게 ADMIN 역할을 부여한다.
 * <b>관리자가 0명일 때만</b> 열리며 성공 시 새 토큰을 즉시 발급한다. 관리자가 한 명이라도 생기면
 * 이 창구는 누구에게도 열리지 않는다.
 *
 * <p>⚠ <b>역할 보유자를 거절하는 조건을 되살리지 말 것</b> — 창이 열려 있는 동안에는 역할 보유
 * 여부가 결과를 가르지 않는다. 되살리면 진입 시 자동 등록이 <b>같은 요청의 앞단</b>에서 작업자
 * 역할을 부여하므로 이 창구가 누구에게도 도달 불가능해진다(API-007 v14).
 *
 * <p>보안 정책 요약은 {@link RoleClaimService} Javadoc 참조.
 *
 * @design ADR-055
 * @design API-007
 */
@Tag(name = "Role Claim",
        description = "관리자가 0명일 때만 열리는 부트스트랩 창구 — 인증된 사용자가 관리자 패스워드와 함께 본인에게 ADMIN 역할을 부여한다.")
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class RoleClaimController {

    private final RoleClaimService roleClaimService;

    @Operation(
            summary = "관리자 부트스트랩 (역할 자가부여)",
            description = """
                    내부 채널로 인증한 사용자가 관리자 공유 패스워드와 함께 본인에게 ADMIN 역할을
                    부여한다. 시스템에 ADMIN 이 한 명도 없을 때만 열린다.
                    창이 열려 있는 동안에는 역할 보유 여부가 결과를 가르지 않는다 — 검수자·작업자도
                    관리자가 되며 기존 역할은 관리자로 교체된다.
                    요청 바디의 role 은 결과를 바꾸지 못한다. 성공 시 새 access token 을 반환한다.
                    """)
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "역할 부여 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "PORTAL_USER 입력 등 유효하지 않은 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증 또는 관리자 패스워드 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "부트스트랩 창이 닫혀 있다(ADMIN 1명 이상) 또는 포털 채널 요청이다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "시도 횟수 제한 초과")
    })
    @PostMapping("/role-claim")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<RoleClaimResponse> claim(@Valid @RequestBody RoleClaimRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TokenClaims claims)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.ok(roleClaimService.claim(req, claims));
    }
}
