package kr.co.cudo.authoring.augment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.dto.AugmentSummaryResponse;
import kr.co.cudo.authoring.augment.dto.RejectRequest;
import kr.co.cudo.authoring.augment.service.AugmentRequestService;
import kr.co.cudo.authoring.augment.service.AugmentReviewService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
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

import java.util.List;

/**
 * Phase 9 — 데이터 증강 검수 API.
 * V1.5 정책에 따라 증강 본체는 외부 SFR-07 시스템이며, 본 API 는 결과 검수만 담당.
 */
@Tag(name = "Augment", description = "데이터 증강 검수 — V1.5: 증강 본체는 외부 SFR-07 책임. 저작도구는 결과 검수(승인/반려)만 제공.")
@RestController
@RequestMapping("/v1/augments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AugmentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AugmentReviewService service;
    private final AugmentRequestService requestService;

    /**
     * 증강 결과 조회.
     * - srcSn 지정 시: 원본 영상의 4종 증강 결과 묶음 (목록)
     * - srcSn 미지정 시: 전체 증강 결과 페이징 (REVIEWER 의 검수 화면용)
     */
    @Operation(
            summary = "증강 결과 조회",
            description = "srcSn 지정 시 4종 묶음 배열, 미지정 시 전체 페이징. REVIEWER/WORKER 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "size 한도 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<?> list(
            @Parameter(description = "원본 영상 PK (없으면 전체 페이징)", example = "1") @RequestParam(required = false) Long srcSn,
            @Parameter(description = "페이지 번호 (0-based, srcSn 미지정 시 사용)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (max 100)", example = "20") @RequestParam(defaultValue = "20") int size) {
        if (srcSn != null) {
            return ApiResponse.ok(service.findBySource(srcSn));
        }
        if (size > MAX_PAGE_SIZE) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "size 한도 초과 (max=" + MAX_PAGE_SIZE + ")");
        }
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(service.listAll(pageable));
    }

    /**
     * 외부 SFR-07 증강 시스템 요청 (REVIEWER 만).
     *
     * <p>검수 완료(APPROVED)된 영상만 요청 가능. 미검수 영상 포함 시 NOT_REVIEWED 와 함께
     * blockedVideoIds 를 응답 data 에 포함하여 400 반환. 외부 미연동 단계이므로 jobId 는
     * placeholder 시퀀스로 발급된다.
     */
    @Operation(
            summary = "증강 요청 (REVIEWER)",
            description = "검수 완료 영상에 대해 외부 SFR-07 증강 시스템에 4종 증강을 요청한다. 미검수 영상 포함 시 NOT_REVIEWED 400."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공 (jobId 발급)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 검증 실패 또는 미검수 영상 포함 (NOT_REVIEWED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @PostMapping("/request")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<AugmentRequestResponse> request(@Valid @RequestBody AugmentRequestRequest req,
                                                       @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(requestService.request(req, actor));
    }

    /**
     * 증강 작업(jobId) 결과 placeholder — V1.5 외부 SFR-07 시스템 연동 결과 폴링용.
     * 현재는 외부 시스템과 연결되지 않아 status=PENDING 의 빈 placeholder 만 반환한다.
     */
    @Operation(
            summary = "증강 작업 결과 조회 (REVIEWER) — placeholder",
            description = "V1.5 외부 SFR-07 결과 폴링용 placeholder. 외부 연동 완료 전까지 status=PENDING 빈 응답."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/{jobId}/result")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<java.util.Map<String, Object>> result(
            @Parameter(description = "증강 jobId", required = true, example = "1") @PathVariable Long jobId) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("status", "PENDING");
        body.put("results", java.util.List.of());
        body.put("message", "외부 SFR-07 시스템 연동 전 — placeholder 응답");
        return ApiResponse.ok(body);
    }

    /**
     * 증강 결과 승인 (REVIEWER 만).
     */
    @Operation(
            summary = "증강 결과 승인 (REVIEWER)",
            description = "외부 SFR-07이 생성한 증강 결과를 검수 승인한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "증강 결과 없음")
    })
    @PostMapping("/{id}/accept")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<AugmentSummaryResponse> accept(@Parameter(description = "증강 결과 PK", required = true, example = "1") @PathVariable Long id,
                                                      @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(service.accept(id, actor));
    }

    /**
     * 증강 결과 반려 (REVIEWER 만, 사유 필수).
     */
    @Operation(
            summary = "증강 결과 반려 (REVIEWER)",
            description = "외부 SFR-07 증강 결과를 사유와 함께 반려한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "사유 누락 등 입력 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "증강 결과 없음")
    })
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<AugmentSummaryResponse> reject(@Parameter(description = "증강 결과 PK", required = true, example = "1") @PathVariable Long id,
                                                      @Valid @RequestBody RejectRequest req,
                                                      @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(service.reject(id, req.reason(), actor));
    }
}
