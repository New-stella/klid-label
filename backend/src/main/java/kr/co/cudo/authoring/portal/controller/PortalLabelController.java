package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.DatamartLabelResponse;
import kr.co.cudo.authoring.portal.dto.PortalFrameLabelsResponse;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelResponse;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * Phase 11 — 포털 채널 라벨링 API.
 *
 * 엔드포인트 (모두 PORTAL_USER 만):
 *  - GET  /v1/portal/datamart/labels: 데이터마트 원본 라벨 Load (페이징)
 *  - POST /v1/portal/user-labels     : 사용자 작업 라벨 저장 (원본 미수정)
 *  - GET  /v1/portal/user-labels     : 본인 작업 라벨 조회 (IDOR 방어)
 *
 * 보안:
 *  - SecurityConfig {@code /v1/portal/**} → PORTAL_USER 만 (다른 역할 403).
 *  - 본인 데이터 검증: PortalLabelService 내부에서 토큰 sub 비교 (CWE-639).
 */
@Tag(name = "Portal Label", description = "포털 채널 라벨링 — PORTAL_USER 전용. 본인 데이터만 접근 가능 (CWE-639 방어).")
@RestController
@RequestMapping("/v1/portal")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalLabelController {

    private final PortalLabelService portalLabelService;

    @Operation(summary = "데이터마트 라벨 Load (V2.0)", description = "rawSn 에 해당하는 원본 라벨 목록 조회 (페이징).")
    @GetMapping("/datamart/labels")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<List<DatamartLabelResponse>> loadDatamartLabels(
            @RequestParam Long rawSn,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        return ApiResponse.ok(portalLabelService.loadDatamartLabels(rawSn, page, size));
    }

    @Operation(summary = "사용자 라벨 저장 (V2.0)", description = "원본 미수정 — LS_PORTAL_USER_LABEL 별도 적재.")
    @PostMapping("/user-labels")
    @PreAuthorize("hasRole('PORTAL_USER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PortalUserLabelResponse> saveUserLabel(
            @Valid @RequestBody PortalUserLabelRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        return ApiResponse.ok(portalLabelService.saveUserLabel(req, actor));
    }

    @Operation(summary = "본인 작업 라벨 조회 (V2.0)", description = "IDOR 방어 — 본인 작업 데이터만 반환.")
    @GetMapping("/user-labels")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<List<PortalUserLabelResponse>> listMyLabels(
            @RequestParam Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        return ApiResponse.ok(portalLabelService.listMyLabels(rawSn, actor));
    }

    @Operation(summary = "포털 프레임 라벨 Load (V2.0/R16)",
            description = "프레임 단위 라벨 조회 — datamart 원본 + 본인 user-label 병합(본인 작업분 우선). PORTAL_USER 전용.")
    @GetMapping("/frames/{srcSn}/labels")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalFrameLabelsResponse> loadFrameLabels(
            @PathVariable Long srcSn,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        return ApiResponse.ok(portalLabelService.loadFrameLabels(srcSn, actor));
    }

    @Operation(summary = "포털 프레임 이미지 서빙 (V2.0/R16)",
            description = "데이터마트 노출(검수 완료) 영상의 비식별 프레임 이미지 바이너리. PORTAL_USER 전용. " +
                    "미승인 영상 403, 프레임/파일 부재 404. Path Traversal(CWE-22) 방어.")
    @GetMapping("/frames/{srcSn}/image")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Resource> getFrameImage(
            @PathVariable Long srcSn,
            @AuthenticationPrincipal TokenClaims actor) throws IOException {
        requireActor(actor);
        return portalLabelService.serveFrameImage(srcSn, actor);
    }

    private void requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }
}
