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
 * 권한 자가 부여 컨트롤러.
 *
 * <p>인증은 되었으나 role 클레임이 비어 있는 사용자가 관리자 공유 패스워드와 함께
 * 본인에게 WORKER/REVIEWER 역할을 부여한다. 성공 시 새 토큰을 즉시 발급한다.
 *
 * <p>보안 정책 요약은 {@link RoleClaimService} Javadoc 참조.
 */
@Tag(name = "Role Claim",
        description = "인증된 사용자가 관리자 패스워드와 함께 본인에게 역할(WORKER/REVIEWER)을 부여한다.")
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class RoleClaimController {

    private final RoleClaimService roleClaimService;

    @Operation(
            summary = "권한 자가 부여",
            description = """
                    인증되었으나 role 이 없는 사용자가 관리자 공유 패스워드와 함께 본인에게
                    WORKER/REVIEWER 역할을 부여한다. 성공 시 새 access token 을 반환한다.
                    """)
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "역할 부여 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "PORTAL_USER 입력 등 유효하지 않은 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증 또는 관리자 패스워드 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 권한이 부여된 사용자"),
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
