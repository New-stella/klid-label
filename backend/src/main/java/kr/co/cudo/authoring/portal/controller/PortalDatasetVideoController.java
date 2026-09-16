package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalDatasetVideoPageResponse;
import kr.co.cudo.authoring.portal.service.PortalDatasetVideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포털 <b>데이터셋 영상 목록</b> 창구 — 소재가 준비된 데이터셋의 영상을 골라 라벨링으로 들어간다. @design API-253
 *
 * <p>인가의 1차 원천은 {@code SecurityConfig} 의 순서 있는 매처({@code /v1/portal/**})이고, 이 창구는
 * 포털 채널의 「사람」 축이다. 서버간 축({@code /v1/portal-system/**})과 다르다.
 *
 * <p>응답에 내부 경로를 싣지 않는다(CWE-209) — 영상 식별자·표시 이름·건수·시각뿐이다.
 *
 * @design ADR-068
 * @design AC-1119
 */
@Tag(name = "Portal Dataset Videos",
        description = "포털 데이터셋 영상 목록 — PORTAL_USER 전용. 소재 준비 뒤 원장에 등록된 영상을 페이지로 돌려준다.")
@RestController
@RequestMapping("/v1/portal/datasets")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalDatasetVideoController {

    private final PortalDatasetVideoService videoService;

    @Operation(summary = "데이터셋 영상 목록",
            description = "소재가 준비된 데이터셋의 등록 영상을 영상 이름 오름차순 페이지로 돌려준다. 등록 상태"
                    + "(IN_PROGRESS·DONE·FAILED)를 함께 싣고, DONE 이 아니면 지금까지 등록된 영상만 싣는다. "
                    + "entrySrcSn·lastSavedAt 은 요청 사용자 기준이다. 소재가 준비되지 않았으면 409.")
    @GetMapping("/{datasetId}/videos")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalDatasetVideoPageResponse> list(
            @PathVariable long datasetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return ApiResponse.ok(videoService.list(datasetId, page, size, actor.sub()));
    }
}
