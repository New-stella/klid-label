package kr.co.cudo.authoring.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.user.dto.UserProfileResponse;
import kr.co.cudo.authoring.user.dto.UserSummaryResponse;
import kr.co.cudo.authoring.user.dto.WorkerSummaryResponse;
import kr.co.cudo.authoring.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "User", description = "사용자 마스터 — 본인 프로필 조회 및 REVIEWER 권한의 작업자 목록 조회.")
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
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
            @Parameter(description = "페이지 번호 (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (max 100)", example = "20") @RequestParam(defaultValue = "20") int size) {
        if (size > MAX_PAGE_SIZE) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "size 한도 초과 (max=" + MAX_PAGE_SIZE + ")");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "userNo"));
        return ApiResponse.ok(userService.searchUsers(keyword, pageable));
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
}
