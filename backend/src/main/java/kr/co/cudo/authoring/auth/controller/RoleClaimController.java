package kr.co.cudo.authoring.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.auth.dto.RoleClaimAvailabilityResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
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
 * @design API-245
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

    /**
     * 부트스트랩 창구 개폐 조회 — 화면이 진입 시점에 열림/닫힘을 미리 묻는다.
     *
     * <p>★ <b>인증만 요구하고 역할은 요구하지 않는다.</b> 주된 호출자가 아직 아무 역할도 부여받지
     * 못한 사용자라, 역할을 조건으로 걸면 정작 필요한 사람이 부르지 못한다.
     *
     * <p>⚠ <b>인증 요구는 두 겹이며 어느 쪽도 지우면 안 된다.</b> 이 경로는 {@code SecurityConfig} 의
     * {@code /v1/auth/**} <b>permitAll</b> 매처 구역에 있어, 그 위의
     * {@code /v1/auth/role-claim/**} 매처가 <b>먼저</b> 인증을 요구해야 미인증 요청이 필터 계층에서
     * 401 로 끝난다. 그 매처를 빼고 이 {@code @PreAuthorize} 만 남기면 미인증 요청이 permitAll 로
     * 통과해 메서드 보안에서 거절되어 <b>401 이 아니라 403</b> 이 되고, 매처만 남기고 이것을 빼면
     * 메서드 계층의 방어가 사라진다(회귀 가드: 토큰 없이 호출하면 401).
     *
     * <p>★ 이 조회가 있어도 <b>제출의 409 갈래는 없어지지 않는다</b> — 조회와 제출 사이에 다른
     * 사람이 최초 관리자가 되면 창은 그 사이에 닫힌다. 사전 조회는 첫 화면이 거짓말하지 않게 하는
     * 수단일 뿐 최종 판정이 아니다.
     *
     * @design API-245
     * @design AC-1098
     */
    @Operation(
            summary = "관리자 부트스트랩 창구 개폐 조회",
            description = """
                    최초 관리자 등록 창구가 지금 열려 있는지를 돌려준다. true=열림(시스템에 관리자가
                    한 명도 없다) / false=닫힘(관리자가 한 명 이상 있다).
                    개폐 판정은 등록 창구(POST /v1/auth/role-claim)가 거절을 가릴 때 쓰는 것과 같은
                    조회다 — 두 창구의 판정이 갈리지 않는다.
                    응답에 관리자 인원수는 담지 않는다.
                    인증만 요구하고 역할은 요구하지 않는다 — 주된 호출자가 역할 미부여 사용자다.
                    ★ 이 값은 순간의 상태다. 조회 뒤 제출 전에 다른 사람이 최초 관리자가 되면 창은
                    그 사이에 닫히므로, 호출하는 화면은 제출이 409 로 거절되는 갈래를 유지해야 한다.
                    """)
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공 — data.available 에 열림/닫힘만 담긴다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "미인증. 미인증자에게는 창구 개폐 여부를 알리지 않는다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "포털 채널 요청이다 — 관리자 부트스트랩은 내부 채널의 개념이다")
    })
    @GetMapping("/role-claim/availability")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<RoleClaimAvailabilityResponse> availability() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TokenClaims claims)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.ok(roleClaimService.availability(claims));
    }
}
