package kr.co.cudo.authoring.review.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.SortAllowlist;
import kr.co.cudo.authoring.review.dto.FrameListResponse;
import kr.co.cudo.authoring.review.dto.IssueResponse;
import kr.co.cudo.authoring.review.dto.ApproveRequest;
import kr.co.cudo.authoring.review.dto.RejectRequest;
import kr.co.cudo.authoring.review.dto.ReviewResponse;
import kr.co.cudo.authoring.review.dto.ReviewSearchCondition;
import kr.co.cudo.authoring.review.dto.ReviewSummaryResponse;
import kr.co.cudo.authoring.review.service.ReviewService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
@org.springframework.validation.annotation.Validated
public class ReviewController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReviewService reviewService;

    /** 정렬 미지정/폴백 기본값 — 제출일(UPD_DT) 최신순. 구 JPQL 의 정적 ORDER BY 와 동일(R8). */
    private static final Sort DEFAULT_REVIEW_SORT = Sort.by(Sort.Direction.DESC, "updDt");

    /**
     * REVIEWER 의 검수 목록 (status/검색어 필터, 정렬, 페이징).
     */
    @Operation(
            summary = "검수 목록 조회 (REVIEWER)",
            description = "검수 워크플로우 목록을 서버 필터/정렬/페이징으로 반환한다.\n\n" +
                    "- **status**: BE 코드만 수용한다(PENDING/IN_REVIEW/APPROVED/REJECTED). 미지정이면 전체. " +
                    "검수 대상이 아닌 배치/작업 상태(ASSIGNED/BATCH_QUEUED/PROCESSING/COMPLETED/FAILED)는 " +
                    "**어떤 조합에서도 노출되지 않는다** — 화이트리스트와의 교집합만 반환하므로 밖의 값을 주면 " +
                    "400 이 아니라 **빈 결과(200)** 가 된다.\n" +
                    "- **q**: 영상명(CCTV 명, 없으면 VMS_CCTV_ID 폴백)·작업자명 부분일치. DB 단계에서 적용되므로 " +
                    "totalElements 가 필터 결과 기준으로 정확하다. 빈 값/공백만 보내면 필터 미적용.\n" +
                    "- **sort**: allowlist(submittedAt/updDt/videoId/status) 밖이면 **400 이 아니라 무시**하고 " +
                    "기본 정렬(제출일 최신순)로 폴백한다 — 다른 화면의 정렬 키가 URL 에 남은 채 진입해도 목록이 죽지 않는다. " +
                    "정렬 항목 개수 상한 초과도 동일하게 폴백. 미지정 시 제출일 최신순. " +
                    "동일 제출일에서 페이지 경계가 흔들리지 않도록 PK(videoId) 내림차순 tie-break 가 항상 마지막에 붙는다.\n\n" +
                    "- **needsRecheck**(Phase 7b 추가 필드): 검수 승인 이후 라벨/메타가 수정되어 재검토가 " +
                    "필요한 영상인가. `true` 면 이미 APPROVED 여도 다시 확인 후 재승인해야 관제 재통지가 나간다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공 (허용되지 않은 정렬 키는 무시하고 기본 정렬)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "size 한도 초과, 과대 길이 검색어"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/reviews")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Page<ReviewResponse>> list(
            @Parameter(description = "검수 상태 (PENDING/IN_REVIEW/APPROVED/REJECTED). 그 밖의 값은 빈 결과.")
            @RequestParam(required = false) String status,
            @Parameter(description = "검색어 (선택) — 영상명·작업자명 부분일치.", example = "강남")
            @RequestParam(required = false)
            @Size(max = 100, message = "검색어는 100자 이하여야 합니다") String q,
            @Parameter(description = "페이지 번호 (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (max 100)", example = "20") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "정렬 (submittedAt|updDt|videoId|status,asc|desc). "
                    + "미지정·미등록 키·개수 초과는 모두 제출일 최신순으로 폴백(400 아님).",
                    example = "submittedAt,desc")
            Sort sort,
            @AuthenticationPrincipal TokenClaims actor) {
        if (size > MAX_PAGE_SIZE) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "size 한도 초과 (max=" + MAX_PAGE_SIZE + ")");
        }
        // 정렬 키 화이트리스트 (CWE-20/CWE-209) — 미등록 키는 쿼리에 절대 닿지 않는다.
        // 단 검수목록은 관용(lenient) 모드다: 변경 전 이 API 는 sort 를 받지도 않아 어떤 값이 붙어도
        // 200 이었으므로, 미등록 키를 400 으로 만들면 FE 가 URL 에 보존·재전송하는 다른 화면의 정렬 키
        // (북마크·뒤로가기)로 목록 전체가 죽는다(R8/AC-8). 무시하고 기본 정렬로 폴백 + WARN 로그.
        // ※ 작업목록(/v1/tasks/board)은 변경 전에도 Pageable 을 받아 잘못된 키가 500 이었으므로 strict 유지.
        // page/size 는 기존 계약(RequestParam + 상한 400)을 그대로 유지하고 정렬만 추가로 해석한다(R8).
        Pageable pageable = PageRequest.of(page, size,
                SortAllowlist.resolveLenient(sort, SortAllowlist.REVIEW, DEFAULT_REVIEW_SORT));
        return ApiResponse.ok(reviewService.list(new ReviewSearchCondition(status, q), pageable, actor));
    }

    /**
     * 검수 목록 KPI 카드 집계 (REVIEWER).
     */
    @Operation(
            summary = "검수 목록 KPI 집계 (REVIEWER)",
            description = "검수 상태 4종 건수를 **필터 결과 전체 기준**으로 반환한다(현재 페이지가 아니다).\n\n" +
                    "- **q 는 목록과 동일하게 반영**된다.\n" +
                    "- **status 는 전달돼도 무시**한다 — KPI 카드 자체가 status 선택지이므로, 이미 status 로 " +
                    "좁혀진 집합 위에서 세면 항상 1개 카드만 값을 갖는다.\n" +
                    "- **sort 는 파라미터로 받지 않는다** — 목록과 동일한 쿼리스트링(sort 포함)을 그대로 보내도 " +
                    "무시되며 400 이 아니다(목록의 정렬 폴백 정책과 동일한 취지).\n" +
                    "- 검수 대상이 아닌 배치/작업 상태는 어느 버킷에도 합산되지 않는다.\n" +
                    "- 불변식: total == pending + inReview + approved + rejected.\n" +
                    "- 목록과 별도 요청이므로 각 값은 조회 시점 스냅샷이다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "과대 길이 검색어"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/reviews/summary")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ReviewSummaryResponse> summary(
            @Parameter(description = "검수 상태 — 집계에서는 무시된다(카드 자체가 이 값의 선택지). "
                    + "목록과 동일한 쿼리스트링을 그대로 보낼 수 있도록 파라미터만 허용한다.")
            @RequestParam(required = false) String status,
            @Parameter(description = "검색어 (선택) — 영상명·작업자명 부분일치.", example = "강남")
            @RequestParam(required = false)
            @Size(max = 100, message = "검색어는 100자 이하여야 합니다") String q,
            @AuthenticationPrincipal TokenClaims actor) {
        // status 는 조건 객체에 담지 않는다 — 서비스/리포지토리 어느 단계에서도 집계 대상이 좁혀지면 안 된다.
        return ApiResponse.ok(reviewService.summarize(new ReviewSearchCondition(null, q), actor));
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
            description = "REVIEWER가 검수를 승인한다. 상태: IN_REVIEW → APPROVED. 낙관적 잠금으로 동시 승인 충돌 시 409.\n\n" +
                    "- 승인 이후 라벨/메타가 수정되어 응답 `needsRecheck=true` 인 APPROVED 영상은 " +
                    "상태 전이 없이(멱등) 재승인할 수 있다 — 재승인 성공 시 응답의 `needsRecheck` 는 다시 `false` 로 돌아온다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "라벨이 있는 영상에 noLabelConfirmed=true 를 보낸 경우"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동시 승인 충돌, 상태 전이 불가, 또는 라벨 0건 영상을 확인 없이 승인"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 누락 신고 구간(재비식별 대기) — 검수 승인 차단")
    })
    @PostMapping("/reviews/{videoId}/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ReviewResponse> approve(@Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long videoId,
                                               @RequestBody(required = false) ApproveRequest req,
                                               @AuthenticationPrincipal TokenClaims actor) {
        // 바디는 선택이다 — 미첨부(null)면 기존 승인과 완전히 동일하게 동작한다(하위호환).
        // negative sample(라벨 0건) 승인만 검수자의 명시 확인(noLabelConfirmed=true)을 요구한다.
        return ApiResponse.ok(reviewService.approve(videoId, req, actor));
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
