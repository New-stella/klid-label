package kr.co.cudo.authoring.augment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.augment.dto.AugmentSummaryResponse;
import kr.co.cudo.authoring.augment.dto.RejectRequest;
import kr.co.cudo.authoring.augment.service.AugmentReviewService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
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

    private final AugmentReviewService service;

    /**
     * 원본 영상의 4종 증강 결과 묶음 조회 (REVIEWER/WORKER 모두 조회 가능).
     */
    @Operation(
            summary = "원본 영상의 증강 결과 묶음 조회",
            description = "WINTER/NIGHT/RAIN/RESOLUTION 4종 증강 결과를 srcSn으로 조회. REVIEWER/WORKER 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<List<AugmentSummaryResponse>> findBySource(@Parameter(description = "원본 영상 PK", required = true, example = "1") @RequestParam Long srcSn) {
        return ApiResponse.ok(service.findBySource(srcSn));
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
