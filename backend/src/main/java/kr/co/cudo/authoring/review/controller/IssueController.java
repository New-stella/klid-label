package kr.co.cudo.authoring.review.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.dto.IssueCommentRequest;
import kr.co.cudo.authoring.review.dto.IssueCommentResponse;
import kr.co.cudo.authoring.review.dto.IssueCreateRequest;
import kr.co.cudo.authoring.review.dto.IssueThreadResponse;
import kr.co.cudo.authoring.review.service.IssueThreadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 1 — 이슈 스레드 API (검수자↔작업자 양방향 소통 채널).
 *
 * <p>WORKER 의 문의 등록·열람·댓글, REVIEWER 의 댓글·해소를 처리한다. 세부 인가(본인 배정·작성자
 * 소유 검증)는 {@link IssueThreadService} 에서 수행한다 — {@code @PreAuthorize} 는 역할 1차 게이트.
 */
@Tag(name = "Issue Thread", description = "이슈 스레드 — WORKER 문의 등록·열람, REVIEWER 댓글·해소. 반려 이력과 문의가 통합 스레드로 조회된다.")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class IssueController {

    private final IssueThreadService issueThreadService;

    @Operation(
            summary = "문의 등록 (WORKER 본인 배정 / REVIEWER)",
            description = "영상(rawSn)에 문의(INQUIRY)를 등록한다. WORKER 는 본인 배정 영상만, REVIEWER 는 전체 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 / 권한 없음")
    })
    @PostMapping("/videos/{rawSn}/issues")
    @PreAuthorize("hasAnyRole('WORKER','REVIEWER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IssueThreadResponse> create(
            @Parameter(description = "영상 PK(RAW_SN)", required = true, example = "1") @PathVariable Long rawSn,
            @Valid @RequestBody IssueCreateRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(issueThreadService.createInquiry(rawSn, req, actor));
    }

    @Operation(
            summary = "이슈 스레드 목록 조회 (REVIEWER 전체 / WORKER 배정·작성)",
            description = "영상의 반려 이력 + 문의를 통합 스레드(REG_DT 오름차순)로 반환한다. 각 스레드에 댓글(시간순) 포함. " +
                    "WORKER 는 현재 배정 영상 또는 본인 작성 이슈가 있는 영상만 열람 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "조회 권한 없음")
    })
    @GetMapping("/videos/{rawSn}/issues")
    @PreAuthorize("hasAnyRole('WORKER','REVIEWER')")
    public ApiResponse<List<IssueThreadResponse>> list(
            @Parameter(description = "영상 PK(RAW_SN)", required = true, example = "1") @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(issueThreadService.listThreads(rawSn, actor));
    }

    @Operation(
            summary = "이슈 댓글 작성 (WORKER 배정·작성자 / REVIEWER)",
            description = "issueSn 의 이슈에 댓글을 단다. REVIEWER 댓글은 OPEN 문의를 ANSWERED 로 전이. " +
                    "해소된(RESOLVED) 문의에는 댓글 불가(409)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 (IDOR)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "이슈 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "해소된 문의 / 동시성 충돌")
    })
    @PostMapping("/issues/{issueSn}/comments")
    @PreAuthorize("hasAnyRole('WORKER','REVIEWER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IssueCommentResponse> comment(
            @Parameter(description = "이슈 PK(DATA_ISSUE_SN)", required = true, example = "1") @PathVariable Long issueSn,
            @Valid @RequestBody IssueCommentRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(issueThreadService.addComment(issueSn, req, actor));
    }

    @Operation(
            summary = "이슈 해소 (REVIEWER)",
            description = "issueSn 의 문의를 RESOLVED 로 전이한다. 이미 RESOLVED 면 멱등(정상 응답)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "이슈 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동시성 충돌")
    })
    /**
     * POST {id}/resolve 액션 서브리소스는 기존 NoticeController publish 선례와 일관된 예외 패턴
     * (REST 순수 리소스 모델이 아닌 상태 전이 액션이라 동사형 서브리소스를 허용한다).
     */
    @PostMapping("/issues/{issueSn}/resolve")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> resolve(
            @Parameter(description = "이슈 PK(DATA_ISSUE_SN)", required = true, example = "1") @PathVariable Long issueSn,
            @AuthenticationPrincipal TokenClaims actor) {
        issueThreadService.resolve(issueSn, actor);
        return ApiResponse.ok(null);
    }
}
