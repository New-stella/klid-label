package kr.co.cudo.authoring.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.auth.dto.AdminPasswordChangeRequest;
import kr.co.cudo.authoring.auth.service.AdminPasswordService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.common.security.adminsession.RequiresAdminSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 공유 패스워드 교체 창구. [@design API-223] [@design ADR-046]
 *
 * <p>자격이 배포 설정에만 있으면 그것을 바꾸는 유일한 길이 <b>재배포</b>다. 사람이 바뀌어도 자격이
 * 그대로 남는다는 뜻이라, 운영 중에 바꿀 수 있는 창구를 둔다.
 *
 * <p>이 창구는 <b>어떤 요청도 예외 없이</b> 유효창을 요구하므로 {@link RequiresAdminSession} 표식을
 * 쓴다(요구가 요청 내용에 따라 갈리는 창구가 아니다).
 */
@Tag(name = "Admin Password",
        description = "관리자 공유 패스워드 교체 — 검수자 권한 + 관리자 유효창 + 현재 패스워드 재확인이 함께 성립해야 한다.")
@RestController
@RequestMapping("/v1/manage/admin-password")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AdminPasswordController {

    private final AdminPasswordService adminPasswordService;

    @Operation(
            summary = "관리자 패스워드 교체 (REVIEWER + 관리자 유효창)",
            description = """
                    관리자 공유 패스워드를 교체한다. 요구는 셋이 함께 성립해야 한다 —
                    검수자 권한, 유효한 관리자 유효창(X-Admin-Session), 현재 패스워드 재확인이다.

                    교체가 성공하면 그 전에 발급된 모든 유효창이 무효가 된다 — 방금 이 요청에 쓴 것도
                    포함된다. 이어서 관리 기능을 쓰려면 새 패스워드로 다시 열어야 한다.

                    새 패스워드가 현재 값과 같으면 거부한다(바뀌지도 않았는데 유효창만 전부 끊기는
                    일을 막는다). 값은 응답에도 로그에도 남지 않는다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "교체 성공. data 는 null 이다 — 바뀐 값이나 그 해시를 응답에 싣지 않는다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "새 패스워드가 길이 규칙에 맞지 않거나 현재 값과 같다. 거부 사유에 값을 싣지 않는다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "인증 실패 또는 현재 패스워드 불일치. 어느 쪽이 틀렸는지 이상은 알리지 않는다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "검수자 권한이 없거나 관리자 유효창이 없거나 만료됐다. 두 사유를 구분해 알리지 않는다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "시도가 너무 잦다. 유효창 발급과 같은 축에서 제한한다.")
    })
    @Parameter(in = ParameterIn.HEADER, name = AdminSessionGate.HEADER, required = true,
            description = "관리자 유효창이 발급한 단기 토큰. 없거나 만료됐으면 403 이다.")
    @PutMapping
    @PreAuthorize("hasRole('REVIEWER')")
    @RequiresAdminSession
    public ApiResponse<Void> change(@Valid @RequestBody AdminPasswordChangeRequest request,
                                    @AuthenticationPrincipal TokenClaims claims) {
        adminPasswordService.change(request, claims);
        return ApiResponse.ok(null);
    }
}
