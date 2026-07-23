package kr.co.cudo.authoring.augment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.augment.dto.AugmentJobResponse;
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
     * 증강 잡 카드(영상 단위 그룹) 조회 — FE {@code AugmentJob} 계약 정합.
     * - srcSn 미지정 시: 전체 증강을 영상 단위로 그룹핑한 잡 카드 페이징
     * - srcSn 지정 시: 해당 원본 영상의 잡 카드만 Page 로 반환 (필터)
     *
     * <p>두 분기 모두 {@code Page<AugmentJobResponse>} 를 반환한다(FE listAugmentJobs 동일 타입 기대).
     */
    @Operation(
            summary = "증강 잡 카드 조회",
            description = "영상 단위로 그룹핑한 잡 카드 페이징. srcSn 지정 시 해당 영상만 필터. REVIEWER/WORKER 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "size 한도 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<org.springframework.data.domain.Page<AugmentJobResponse>> list(
            @Parameter(description = "필터용 대표프레임 ID. 이 값은 LS_DATA_SRC.SRC_SN(원본 영상 대표프레임 PK)이며, "
                    + "응답의 videoId(=원본 RAW_SN)와 다른 도메인 값이다. job.videoId(RAW_SN)를 이 파라미터로 넘기면 "
                    + "조용히 다른 영상이 조회되니 절대 혼용 금지. 없으면 전체 페이징.", example = "1")
            @RequestParam(required = false) Long srcSn,
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
     * 증강 작업(jobId) 결과 상태 조회 — FE 결과 화면(SCR-AUG-002)의 실제 상태 표시용.
     *
     * <p>프레임별 results 본문은 외부 SFR-07 시스템 연동 전이라 비워 두지만, {@code status} 는 해당
     * 원본 영상(jobId) 증강 row 의 실제 집계 상태(COMPLETED|FAILED|PROCESSING)를 반환한다. 이로써
     * 완료/실패 파생이 결과 비어 있다는 이유로 무조건 "처리 중"으로 오표시되던 결함을 제거한다.
     */
    @Operation(
            summary = "증강 작업 결과 조회 (REVIEWER)",
            description = "해당 원본 영상 증강 row 의 실제 집계 상태(COMPLETED|FAILED|PROCESSING)를 반환한다. "
                    + "프레임별 results 본문은 외부 SFR-07 연동 전이라 비어 있다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/{jobId}/result")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<java.util.Map<String, Object>> result(
            @Parameter(description = "증강 jobId(=원본 RAW_SN)", required = true, example = "1") @PathVariable Long jobId) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("status", service.aggregateResultStatus(jobId));
        body.put("results", java.util.List.of());
        body.put("message", "프레임별 결과 본문은 외부 SFR-07 시스템 연동 전 — status 만 실제 집계값을 반영합니다.");
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
