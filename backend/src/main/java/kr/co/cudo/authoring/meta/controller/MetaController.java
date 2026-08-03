package kr.co.cudo.authoring.meta.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.meta.dto.MetaResponse;
import kr.co.cudo.authoring.meta.dto.MetaReviewRejectRequest;
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
import kr.co.cudo.authoring.meta.service.MetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Meta", description = "외부 시계열 메타데이터 검토 — V1.7 정책: 외부 시스템이 생성한 메타를 저작도구에서 검토·수정만. 본인 배정 검증 적용.")
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class MetaController {

    private final MetaService metaService;

    @Operation(
            summary = "프레임 시계열 메타 조회",
            description = "외부 시스템(SFR-03)이 생성한 시계열 메타를 조회한다. WORKER는 본인 배정 프레임만. "
                    + "응답 items 는 시계열 메타만 담으며, 영상 기술메타(video.*)는 별도 technicalMeta 로 분리해 반환한다(읽기 전용)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임/메타 없음")
    })
    @GetMapping("/v1/frames/{srcSn}/meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<MetaResponse> getMeta(@Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
                                              @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(metaService.getByFrame(srcSn, actor));
    }

    @Operation(
            summary = "프레임 시계열 메타 수정",
            description = "외부 메타 검토 결과 수정사항을 반영한다 (저작도구 책임 범위는 검토·수정만). "
                    + "영상 기술메타(video.*) 키는 수정 대상이 아니며 요청에 포함되면 400."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임/메타 없음")
    })
    @PutMapping("/v1/frames/{srcSn}/meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<MetaResponse> updateMeta(@Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
                                                 @Valid @RequestBody MetaUpdateRequest req,
                                                 @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(metaService.update(srcSn, req, actor));
    }

    @Operation(
            summary = "메타 검토 승인 (Phase 5)",
            description = "VLM 자동 생성 또는 외부 시스템 송신 메타를 REVIEWER 가 승인한다. LS_DATA_META_REVIEW.RVW_STTS_CD='APPROVED' 갱신."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "검토 row 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 검토 완료")
    })
    @PostMapping("/v1/meta/{metaReviewSn}/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Void> approveReview(@Parameter(description = "메타 검토 PK", required = true) @PathVariable Long metaReviewSn,
                                            @AuthenticationPrincipal TokenClaims actor) {
        metaService.approveReview(metaReviewSn, actor);
        return ApiResponse.ok(null);
    }

    @Operation(
            summary = "메타 검토 반려 (Phase 5)",
            description = "VLM 자동 생성 또는 외부 시스템 송신 메타를 REVIEWER 가 반려한다. 사유 필수."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "사유 누락 등 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "검토 row 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 검토 완료")
    })
    @PostMapping("/v1/meta/{metaReviewSn}/reject")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Void> rejectReview(@Parameter(description = "메타 검토 PK", required = true) @PathVariable Long metaReviewSn,
                                           @Valid @RequestBody MetaReviewRejectRequest req,
                                           @AuthenticationPrincipal TokenClaims actor) {
        metaService.rejectReview(metaReviewSn, req.reason(), actor);
        return ApiResponse.ok(null);
    }
}
