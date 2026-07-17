package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalUploadLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUploadLabelResponse;
import kr.co.cudo.authoring.portal.service.PortalUploadLabelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 4 — 포털 업로드 프레임 라벨 CRUD + 내보내기/다운로드 API (PORTAL_USER 전용).
 *
 * <p>보안:
 * <ul>
 *   <li>SecurityConfig {@code /v1/portal/**} → PORTAL 채널 + PORTAL_USER 만(다른 채널/역할 403).</li>
 *   <li>메서드 {@code @PreAuthorize("hasRole('PORTAL_USER')")} 이중 방어.</li>
 *   <li>소유자 스코프(IDOR/CWE-639)는 서비스에서 소유자 스코프 리포지토리로 강제 — 부재/타인 403.</li>
 *   <li>라벨 배열 상한(500)은 {@code @Size}(컨트롤러) + 서비스 이중 강제(CWE-770).</li>
 * </ul>
 */
@Slf4j
@Tag(name = "Portal Upload Label", description = "포털 업로드 프레임 라벨 CRUD/내보내기 — PORTAL_USER 전용, 본인 자산만.")
@RestController
@RequestMapping("/v1/portal/uploads")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class PortalUploadLabelController {

    /** 프레임당 라벨 개수 상한(서비스와 동일 — 컨트롤러 1차 방어). */
    private static final int MAX_LABELS_PER_FRAME = 500;

    private final PortalUploadLabelService portalUploadLabelService;

    @Operation(summary = "프레임 라벨 전체교체(PUT)",
            description = "본인 자산 READY 상태 프레임만. 멱등 — 빈 배열은 전체 삭제. 검증 실패 시 기존 라벨 유지(400).")
    @PutMapping("/frames/{uldFrmeSn}/labels")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<List<PortalUploadLabelResponse>> replaceLabels(
            @PathVariable Long uldFrmeSn,
            @RequestBody(required = false)
            @Size(max = MAX_LABELS_PER_FRAME, message = "프레임당 라벨은 최대 500 개까지 허용됩니다.")
            @Valid List<PortalUploadLabelRequest> labels,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        return ApiResponse.ok(portalUploadLabelService.replaceLabels(uldFrmeSn, owner, labels));
    }

    @Operation(summary = "프레임 라벨 목록", description = "본인 자산 프레임의 라벨만. 타 사용자/부재 자산은 403.")
    @GetMapping("/frames/{uldFrmeSn}/labels")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<List<PortalUploadLabelResponse>> listLabels(
            @PathVariable Long uldFrmeSn,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        return ApiResponse.ok(portalUploadLabelService.listLabels(uldFrmeSn, owner));
    }

    @Operation(summary = "자산 라벨 내보내기(export)",
            description = "본인 자산의 메타+프레임+라벨을 JSON attachment 로 다운로드. 서버 생성 고정 파일명.")
    @GetMapping("/{uldSn}/export")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<byte[]> exportLabels(
            @PathVariable Long uldSn,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        return portalUploadLabelService.exportLabels(uldSn, owner);
    }

    @Operation(summary = "원본 파일 다운로드", description = "본인 자산 원본 파일. DB 확정 MIME + nosniff + attachment.")
    @GetMapping("/{uldSn}/file")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable Long uldSn,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        return portalUploadLabelService.downloadFile(uldSn, owner);
    }

    private String requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return actor.sub();
    }
}
