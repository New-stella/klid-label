package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalAutolabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUploadResponse;
import kr.co.cudo.authoring.portal.entity.LsPortalUserVideo;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import kr.co.cudo.authoring.portal.service.PortalUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 11 — 포털 채널 라벨링/업로드 목록 API.
 *
 * 엔드포인트 (모두 PORTAL_USER 만):
 *  - GET  /v1/portal/uploads     : 본인 업로드 영상 목록 (배열, 최신순)
 *  - POST /v1/portal/labels      : 수동 라벨(체험) — echo only
 *  - POST /v1/portal/autolabel   : YOLO 추론 체험
 *
 * 보안:
 *  - SecurityConfig {@code /v1/portal/**} → PORTAL_USER 만 (다른 역할 403).
 *  - 본인 영상 검증: PortalLabelService 내부에서 portalVideoSn vs 토큰 sub 비교 (CWE-639).
 */
@Tag(name = "Portal Label", description = "포털 채널 라벨링/업로드 목록 — PORTAL_USER 전용. 본인 데이터만 접근 가능 (CWE-639 방어).")
@RestController
@RequestMapping("/v1/portal")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalLabelController {

    private final PortalLabelService portalLabelService;
    private final PortalUploadService portalUploadService;

    @Operation(
            summary = "본인 업로드 영상 목록 조회 (포털)",
            description = "포털 사용자가 업로드한 본인 영상 전체를 배열로 반환 (최신순). 본인 데이터 범위가 작아 페이징 미사용."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "포털 토큰 없음/검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "PORTAL_USER 권한 없음")
    })
    @GetMapping("/uploads")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<List<PortalUploadResponse>> listMyUploads(
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        List<LsPortalUserVideo> result = portalUploadService.listMyUploads(actor.sub());
        return ApiResponse.ok(result.stream().map(PortalUploadResponse::from).toList());
    }

    @Operation(
            summary = "오토라벨링 체험 (YOLO 추론)",
            description = "포털 사용자가 본인 업로드 영상에 대해 YOLO 오토라벨링을 체험. 결과는 저장되지 않음 (체험용)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "포털 토큰 없음/검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "PORTAL_USER 권한 없음 또는 본인 영상 아님 (IDOR 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "ai-server 연동 실패")
    })
    @PostMapping("/autolabel")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<YoloResponse> autolabel(@Valid @RequestBody PortalAutolabelRequest req,
                                                @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        return ApiResponse.ok(portalLabelService.autolabel(req, actor));
    }

    @Operation(
            summary = "수동 라벨 체험 (echo)",
            description = "포털 사용자가 수동으로 그린 라벨을 echo로 반환 (체험용, 저장되지 않음)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "포털 토큰 없음/검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "PORTAL_USER 권한 없음 또는 본인 영상 아님")
    })
    @PostMapping("/labels")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalLabelRequest> manualLabel(@Valid @RequestBody PortalLabelRequest req,
                                                        @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        return ApiResponse.ok(portalLabelService.acceptLabel(req, actor));
    }

    private void requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }
}
