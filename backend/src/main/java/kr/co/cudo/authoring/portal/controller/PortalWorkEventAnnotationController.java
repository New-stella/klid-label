package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalEventAnnotationResponse;
import kr.co.cudo.authoring.portal.dto.PortalEventAnnotationUpdateRequest;
import kr.co.cudo.authoring.portal.service.PortalWorkEventAnnotationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포털 작업 화면의 이벤트 어노테이션 창구 — 영상 단위라 프레임 참조가 없다.
 *
 * <p>보안 규약은 {@link PortalWorkMetaController} 와 같다.
 *
 * @design API-236
 * @design API-237
 */
@Tag(name = "Portal Work Event Annotation",
        description = "포털 작업 화면 이벤트 어노테이션 Load·저장 — PORTAL_USER 전용. 데이터마트 자산은 포털 전용 오버레이에 단방향 적재하고, 본인 업로드 자산은 그 자산의 원장에 그대로 적재한다.")
@RestController
@RequestMapping("/v1/portal")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalWorkEventAnnotationController {

    private final PortalWorkEventAnnotationService portalWorkEventAnnotationService;

    @Operation(summary = "포털 이벤트 어노테이션 Load",
            description = "데이터마트 자산은 원본과 본인 오버레이를 병합해 내려준다(본인 작업분 우선). "
                    + "본인이 올린 자산은 오버레이를 거치지 않고 그 자산의 어노테이션을 그대로 내려준다. "
                    + "본문은 내부 원장과 같은 구조체를 그대로 담는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "병합된 이벤트 어노테이션"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 작업 대상이 아님(실재 여부를 드러내지 않는 응답)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "대상 영상 없음")
    })
    @GetMapping("/videos/{rawSn}/event-annotation")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalEventAnnotationResponse> loadAnnotation(
            @Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(portalWorkEventAnnotationService.load(rawSn, requireActor(actor)));
    }

    @Operation(summary = "포털 이벤트 어노테이션 저장(오버레이 적재)",
            description = "적재 키가 (포털사용자, 영상)이라 영상당 한 벌이고 다시 저장하면 덮어쓴다. "
                    + "데이터마트 자산은 원본과 승인 시점 동결본을 수정하지 않는다(단방향 — 관제 통지·산출물 재생성을 일으키지 않는다).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "적재 결과(저장 후 본문)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "본문 구조가 규격과 다름"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 작업 대상이 아님(실재 여부를 드러내지 않는 응답)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "대상 영상 없음")
    })
    @PutMapping("/videos/{rawSn}/event-annotation")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalEventAnnotationResponse> saveAnnotation(
            @Parameter(description = "영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @Valid @RequestBody PortalEventAnnotationUpdateRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(portalWorkEventAnnotationService.save(rawSn, requireActor(actor), request));
    }

    private String requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return actor.sub();
    }
}
