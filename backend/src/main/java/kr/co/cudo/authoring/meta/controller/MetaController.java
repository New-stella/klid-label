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
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
import kr.co.cudo.authoring.meta.service.MetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Meta", description = "외부 시계열 메타데이터 검토 — V1.7 정책: 외부 시스템이 생성한 메타를 저작도구에서 검토·수정만. 본인 배정 검증 적용.")
@RestController
@RequestMapping("/v1/frames")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class MetaController {

    private final MetaService metaService;

    @Operation(
            summary = "프레임 시계열 메타 조회",
            description = "외부 시스템(SFR-03)이 생성한 시계열 메타를 조회한다. WORKER는 본인 배정 프레임만."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임/메타 없음")
    })
    @GetMapping("/{srcSn}/meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<MetaResponse> getMeta(@Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
                                              @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(metaService.getByFrame(srcSn, actor));
    }

    @Operation(
            summary = "프레임 시계열 메타 수정",
            description = "외부 메타 검토 결과 수정사항을 반영한다 (저작도구 책임 범위는 검토·수정만)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임/메타 없음")
    })
    @PutMapping("/{srcSn}/meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<MetaResponse> updateMeta(@Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
                                                 @Valid @RequestBody MetaUpdateRequest req,
                                                 @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(metaService.update(srcSn, req, actor));
    }
}
