package kr.co.cudo.authoring.review.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.dto.FrameListResponse;
import kr.co.cudo.authoring.review.dto.IssueResponse;
import kr.co.cudo.authoring.review.dto.RejectRequest;
import kr.co.cudo.authoring.review.dto.ReviewResponse;
import kr.co.cudo.authoring.review.service.ReviewService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Review", description = "검수 워크플로우 — WORKER가 제출(submit), REVIEWER가 시작/승인/반려를 처리하는 상태 머신.")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ReviewController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReviewService reviewService;

    /**
     * REVIEWER 의 검수 목록 (status 필터, 페이징).
     */
    @Operation(
            summary = "검수 목록 조회 (REVIEWER)",
            description = "검수 워크플로우 상태별 페이징 목록. status 미지정 시 전체. (PENDING/IN_REVIEW/APPROVED/REJECTED)"
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "size 한도 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/reviews")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Page<ReviewResponse>> list(
            @Parameter(description = "검수 상태 (PENDING/IN_REVIEW/APPROVED/REJECTED)") @RequestParam(required = false) String status,
            @Parameter(description = "페이지 번호 (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (max 100)", example = "20") @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal TokenClaims actor) {
        if (size > MAX_PAGE_SIZE) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "size 한도 초과 (max=" + MAX_PAGE_SIZE + ")");
        }
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(reviewService.list(status, pageable, actor));
    }

    /**
     * 검수 단건 상세 조회 — REVIEWER(전체) 또는 본인 LABELER 배정 WORKER.
     * <p>WORKER 는 라벨링 화면 검수제출 가드(작업 상태 조회)용으로 본인 배정 영상만 조회 가능 (IDOR 가드는 서비스에서 검증).
     */
    @Operation(
            summary = "검수 상세 조회 (REVIEWER / 본인 배정 WORKER)",
            description = "videoId 기준 검수 단건 상세를 반환한다. REVIEWER 는 전체, WORKER 는 본인 LABELER 배정 영상만."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 또는 본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "검수 대상 영상 없음")
    })
    @GetMapping("/reviews/{videoId}")
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<ReviewResponse> detail(
            @Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long videoId,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(reviewService.getDetail(videoId, actor));
    }

    /**
     * SCR-REVIEW-002 — 영상의 모든 프레임 + 라벨 일괄 조회 (REVIEWER).
     */
    @Operation(
            summary = "검수 프레임 + 라벨 일괄 조회 (REVIEWER)",
            description = "videoId 기준 모든 프레임 메타와 각 프레임에 속한 라벨 (auto + manual)을 한 번에 반환한다. " +
                    "N+1 회피 — 프레임 1회 + 라벨 단일 IN-쿼리 1회 (총 2회). " +
                    "imageUrl 은 /api/v1/videos/{rawSn}/frames/{frameNo}/image (context-path 포함)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/reviews/{videoId}/frames")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<FrameListResponse> frames(
            @Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long videoId,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(reviewService.listFrames(videoId, actor));
    }

    /**
     * 검수 이슈(반려 사유) 목록 조회 (REVIEWER).
     */
    @Operation(
            summary = "검수 이슈 목록 조회 (REVIEWER)",
            description = "videoId 기준 LS_DATA_ISSUE 이력을 등록일 내림차순으로 반환한다. 비어 있으면 빈 배열."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/reviews/{videoId}/issues")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<List<IssueResponse>> issues(
            @Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long videoId,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(reviewService.listIssues(videoId, actor));
    }

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
     * WORKER 가 검수 제출을 취소 (PENDING → ASSIGNED). 검수 시작 전에만 가능, 본인 배정 영상만.
     */
    @Operation(
            summary = "검수 제출 취소 (WORKER)",
            description = "WORKER가 검수 시작 전(PENDING) 상태에서 제출을 취소하고 작업(ASSIGNED)으로 복귀시킨다. " +
                    "본인 배정 영상만 가능하며, 검수 시작(IN_REVIEW)/승인/반려 후에는 취소할 수 없다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공 (ASSIGNED 복귀)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "취소 불가 상태 (검수 시작 후 등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "WORKER 권한 없음 또는 본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 검수 시작/승인되어 취소 충돌")
    })
    @PostMapping("/reviews/{videoId}/cancel-submit")
    @PreAuthorize("hasRole('WORKER')")
    public ApiResponse<ReviewResponse> cancelSubmit(@Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long videoId,
                                                    @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(reviewService.cancelSubmit(videoId, actor));
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
