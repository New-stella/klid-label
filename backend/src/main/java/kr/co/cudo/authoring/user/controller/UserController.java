package kr.co.cudo.authoring.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.common.security.adminsession.RequiresAdminSession;
import kr.co.cudo.authoring.user.dto.UserProfileResponse;
import kr.co.cudo.authoring.user.dto.UserSummaryResponse;
import kr.co.cudo.authoring.user.dto.UserUpdateRequest;
import kr.co.cudo.authoring.user.dto.WorkerSummaryResponse;
import kr.co.cudo.authoring.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "User", description = "사용자 마스터 — 본인 프로필 조회 및 REVIEWER 권한의 작업자 목록 조회.")
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Validated
public class UserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserService userService;

    @Operation(
            summary = "사용자 마스터 목록 조회 (REVIEWER)",
            description = "REVIEWER 전용. 사용자 관리 화면용. keyword 로 USER_ID/USER_NM/USER_EMAIL 부분일치 검색."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "size 한도 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Page<UserSummaryResponse>> list(
            @Parameter(description = "검색 키워드 (USER_ID/USER_NM/USER_EMAIL 부분일치)") @RequestParam(required = false) String keyword,
            @Parameter(description = "역할 필터 (ADMIN/REVIEWER/WORKER/PORTAL_USER, 선택)")
                @RequestParam(required = false)
                @Pattern(regexp = "^(ADMIN|REVIEWER|WORKER|PORTAL_USER)$") String role,
            @Parameter(description = "페이지 번호 (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (max 100)", example = "20") @RequestParam(defaultValue = "20") int size) {
        if (size > MAX_PAGE_SIZE) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "size 한도 초과 (max=" + MAX_PAGE_SIZE + ")");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "userNo"));
        return ApiResponse.ok(userService.searchUsers(keyword, role, pageable));
    }

    @Operation(
            summary = "내 프로필 조회",
            description = "JWT 토큰의 sub 클레임을 기반으로 본인 프로필을 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음")
    })
    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> me(@AuthenticationPrincipal TokenClaims claims) {
        return ApiResponse.ok(userService.getProfile(claims));
    }

    @Operation(
            summary = "작업자(WORKER) 목록 조회",
            description = "REVIEWER 전용. 배정 가능한 WORKER 사용자 목록과 각 작업자의 현재 배정 건수를 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/workers")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<List<WorkerSummaryResponse>> listWorkers() {
        return ApiResponse.ok(userService.listWorkersWithTaskCount());
    }

    @Operation(
            summary = "사용자 프로필 단건 조회 (관리)",
            description = "REVIEWER 전용. 다른 사용자의 프로필을 조회한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음")
    })
    @GetMapping("/{userNo}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<UserProfileResponse> getById(@Parameter(description = "사용자 PK", required = true, example = "1001") @PathVariable Long userNo,
                                                    @AuthenticationPrincipal TokenClaims claims) {
        return ApiResponse.ok(userService.getById(userNo, claims));
    }

    /**
     * 사용자 역할 변경 — <b>관리자 권한에 관리자 유효창이 가산된다.</b>
     * [@design API-004] [@design ADR-046] [@design ADR-055] [@design AC-056]
     *
     * <p>역할을 바꾸는 것은 운영·관리 성격의 쓰기다. 유효창은 관리자 권한을 <b>대체하지 않고 가산</b>된다
     * — {@code @PreAuthorize} 는 그대로 필요하며 유효창이 역할을 승격시키지도 않는다.
     *
     * <p>★ <b>검수자로는 열리지 않는다</b>(ADR-055). 검수자가 스스로 역할을 바꿀 수 있으면 권한
     * 분리가 성립하지 않는다 — 관리자 패스워드로 유효창만 열면 자기 자신을 관리자로 올릴 수 있다.
     * 관리자는 계층으로 검수자 권한을 물려받으므로 이 상향으로 잃는 기능이 없다.
     *
     * <p>⚠ <b>같은 자원의 조회에는 이 요구를 두지 않는다.</b> 목록·상세뿐 아니라 다른 업무 화면이
     * 작업자 목록을 읽는 경로({@code GET /v1/users/workers})도 <b>검수자</b> 권한만으로 된다 — 조회를
     * 관리자로 올리면 작업 배정 흐름이 끊긴다(@design AC-056).
     *
     * <p>거부는 검수자 권한이 없을 때와 유효창이 없을 때가 <b>응답으로 구분되지 않는다</b>(둘 다 403,
     * 같은 문구). 구분하면 응답 자체가 유효창 상태를 알려주는 신호가 된다(CWE-209).
     *
     * <p>★ <b>바꾼 사람은 인증 주체에서만 온다</b>(@design AC-1018) — {@code TokenClaims.sub} 를
     * 서비스로 넘겨 {@code LS_USER_ROLE.MDFR_ID} 에 남긴다. <b>요청 바디에서 받지 않는다</b>:
     * 바디 값은 위조 가능해서, 그것을 기록하면 감사가 "본인이 주장한 사람" 이 된다.
     * {@link UserUpdateRequest} 에 주체 필드를 추가하지 말 것.
     */
    @Operation(
            summary = "사용자 역할 변경 (ADMIN + 관리자 유효창)",
            description = "ADMIN 전용. role(ADMIN|REVIEWER|WORKER|PORTAL_USER)을 저작도구 소유 LS_USER_ROLE 에 변경한다. " +
                    "화이트리스트 정규식으로 검증되며 role 미제공 시 변경되지 않는다. (활성/비활성 토글은 관제 소유라 제외) " +
                    "역할 변경은 운영·관리 성격의 쓰기라 관리자 권한에 더해 유효한 관리자 유효창(X-Admin-Session)을 함께 요구한다. " +
                    "마지막 남은 ADMIN 을 다른 역할로 내리는 요청은 409 로 거절된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "ADMIN 권한이 없거나, 유효한 관리자 유효창이 없다(미제출·만료 포함). 두 사유를 응답으로 구분하지 않는다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "마지막 남은 ADMIN 을 다른 역할로 내리려 했다 — ADMIN 이 0명이 되면 부트스트랩 창구가 다시 열린다")
    })
    @Parameter(in = ParameterIn.HEADER, name = AdminSessionGate.HEADER, required = true,
            description = "관리자 유효창이 발급한 단기 토큰. 없거나 만료됐으면 403 이다.")
    @PatchMapping("/{userNo}")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresAdminSession
    public ApiResponse<UserProfileResponse> update(
            @Parameter(description = "사용자 PK", required = true, example = "1001") @PathVariable Long userNo,
            @Valid @RequestBody UserUpdateRequest request,
            @AuthenticationPrincipal TokenClaims claims
    ) {
        // actor 는 인증 주체에서만 — 바디가 아니다(위조 방지). 인증이 없으면 여기 도달하지 않는다.
        return ApiResponse.ok(userService.update(userNo, request, claims == null ? null : claims.sub()));
    }
}
