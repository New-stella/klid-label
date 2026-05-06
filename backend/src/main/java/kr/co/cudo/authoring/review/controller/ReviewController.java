package kr.co.cudo.authoring.review.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.dto.RejectRequest;
import kr.co.cudo.authoring.review.dto.ReviewResponse;
import kr.co.cudo.authoring.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Review", description = "검수 워크플로우 — WORKER가 제출(submit), REVIEWER가 시작/승인/반려를 처리하는 상태 머신.")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * WORKER 가 라벨링 완료 후 검수 제출 (PENDING 으로 전이).
     */
    @Operation(
            summary = "검수 제출 (WORKER)",
            description = "WORKER가 라벨링을 완료하고 검수를 제출한다. 상태: → PENDING."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "WORKER 권한 없음 또는 본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "상태 전이 불가")
    })
    @PostMapping("/reviews/{videoId}/submit")
    @PreAuthorize("hasRole('WORKER')")
    public ApiResponse<ReviewResponse> submit(@Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long videoId,
                                              @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(reviewService.submit(videoId, actor));
    }

    /**
     * REVIEWER 가 검수 시작 (PENDING → IN_REVIEW).
     */
    @Operation(
            summary = "검수 시작 (REVIEWER)",
            description = "REVIEWER가 검수를 시작한다. 상태: PENDING → IN_REVIEW."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "상태 전이 불가")
    })
    @PostMapping("/reviews/{videoId}/start")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ReviewResponse> start(@Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long videoId,
                                             @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(reviewService.startReview(videoId, actor));
    }

    /**
     * REVIEWER 승인 (IN_REVIEW → APPROVED). 동시 승인 시도 시 409 CONFLICT.
     */
    @Operation(
            summary = "검수 승인 (REVIEWER)",
            description = "REVIEWER가 검수를 승인한다. 상태: IN_REVIEW → APPROVED. 낙관적 잠금으로 동시 승인 충돌 시 409."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동시 승인 충돌 또는 상태 전이 불가")
    })
    @PostMapping("/reviews/{videoId}/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ReviewResponse> approve(@Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long videoId,
                                               @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(reviewService.approve(videoId, actor));
    }

    /**
     * REVIEWER 반려 (IN_REVIEW → REJECTED). 사유 필수, LS_DATA_ISSUE 신규 INSERT.
     */
    @Operation(
            summary = "검수 반려 (REVIEWER)",
            description = "REVIEWER가 검수를 반려한다. 상태: IN_REVIEW → REJECTED. 사유 필수이며 LS_DATA_ISSUE에 이력 INSERT."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "사유 누락 등 입력 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "상태 전이 불가")
    })
    @PostMapping("/reviews/{videoId}/reject")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ReviewResponse> reject(@Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long videoId,
                                              @Valid @RequestBody RejectRequest req,
                                              @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(reviewService.reject(videoId, req, actor));
    }
}
