package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalAugmentCreatedResponse;
import kr.co.cudo.authoring.portal.dto.PortalAugmentRequest;
import kr.co.cudo.authoring.portal.service.PortalAugmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포털 업로드 영상 증강 <b>요청 접수</b> 창구 (PORTAL_USER 전용).
 *
 * <p>요청을 거는 자리는 포털 업로드 화면의 자산별 액션이므로 창구도 <b>자산 아래</b>에 둔다.
 * 요청 이후의 현황·결과 확인은 {@link PortalAugmentController} 가 담당한다.
 *
 * <p>관제 증강 요청 창구를 재사용하지 않는다 — 인가 주체와 대상 계보가 다르고, 그 창구가 전제하는
 * <b>검수 완료</b> 조건이 이 경로에는 성립하지 않는다.
 *
 * @design API-231
 * @design SCREEN-033
 */
@Tag(name = "Portal Augment",
        description = "포털 업로드 영상 AI 증강 — 본인이 올린 준비 완료 영상만 대상이며 채택·반려 결정 단계가 없다.")
@RestController
@RequestMapping("/v1/portal/uploads/{uldSn}/augments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalUploadAugmentController {

    private final PortalAugmentService portalAugmentService;

    @Operation(summary = "증강 요청 접수",
            description = "본인이 올린 준비 완료 영상 한 건에 증강을 요청한다. 데이터마트에서 불러온 영상은 "
                    + "대상이 아니다. 응답은 접수 사실이지 결과가 아니다 — 결과 도착 여부는 요청 현황 목록·"
                    + "단건 조회에서 확인한다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalAugmentCreatedResponse> request(
            @PathVariable Long uldSn,
            @Valid @RequestBody PortalAugmentRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(portalAugmentService.request(uldSn, req, actor));
    }
}
