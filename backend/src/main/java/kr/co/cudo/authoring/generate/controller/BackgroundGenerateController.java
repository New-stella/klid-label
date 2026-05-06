package kr.co.cudo.authoring.generate.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.generate.dto.BackgroundGenerateRequest;
import kr.co.cudo.authoring.generate.dto.BackgroundGenerateResponse;
import kr.co.cudo.authoring.generate.service.BackgroundGenerateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 9 — 외부 SFR-06/11 생성 시스템에 배경영상 생성 요청 (인터페이스만).
 *
 * <p>V1.5 정책: 본 저작도구는 외부 시스템에 요청만 송신, 결과 산출은 외부 책임.
 */
@Tag(name = "Background Generate", description = "배경영상 생성 요청 — V1.5/V1.8: 외부 SFR-06/11 시스템에 요청만 송신. 결과 산출 책임은 외부.")
@RestController
@RequestMapping("/v1/generate")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class BackgroundGenerateController {

    private final BackgroundGenerateService service;

    @Operation(
            summary = "배경영상 생성 요청 (외부 시스템 연동, REVIEWER 전용)",
            description = "외부 생성형 AI 시스템에 배경영상 생성을 요청한다. 응답은 외부 시스템의 작업 ID이며 결과는 비동기로 외부에서 산출."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "외부 시스템 연동 실패 (서킷 브레이커)")
    })
    @PostMapping("/background")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<BackgroundGenerateResponse> requestBackground(
            @Valid @RequestBody BackgroundGenerateRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(service.requestExternal(req, actor));
    }
}
