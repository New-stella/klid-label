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
import kr.co.cudo.authoring.portal.dto.PortalMetaResponse;
import kr.co.cudo.authoring.portal.dto.PortalMetaUpdateRequest;
import kr.co.cudo.authoring.portal.service.PortalWorkMetaService;
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
 * 포털 작업 화면의 프레임 메타 창구 — 촬영환경·프레임 설명·개인정보 판정·시계열 메타.
 *
 * <p>보안:
 * <ul>
 *   <li>{@code /v1/portal/**} → PORTAL 채널 + PORTAL_USER 만(다른 채널·역할 403).</li>
 *   <li>메서드 {@code @PreAuthorize("hasRole('PORTAL_USER')")} 이중 방어.</li>
 *   <li>소유자 스코프(IDOR / CWE-639)는 서비스가 강제한다 — 본인 작업 대상이 아니면 403 이고
 *       그 본문은 남의 자산인지 미승인 영상인지 <b>구분되지 않는 한 문구</b>다.</li>
 *   <li>본문 크기 상한은 {@code PortalLabelBodySizeFilter} 가 파싱 전에 적용한다(413/411).</li>
 *   <li>비식별 누락 신고 구간은 <b>읽기·쓰기 양쪽</b> 412 — 형제 창구와 같은 코드다. 본인 업로드
 *       자산은 그 구간이 없어 대상이 아니다.</li>
 * </ul>
 *
 * @design API-234
 * @design API-235
 */
@Tag(name = "Portal Work Meta",
        description = "포털 작업 화면 프레임 메타 Load·저장 — PORTAL_USER 전용. 데이터마트 자산은 포털 전용 오버레이에 단방향 적재하고, 본인 업로드 자산은 그 자산의 원장에 그대로 적재한다.")
@RestController
@RequestMapping("/v1/portal")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalWorkMetaController {

    private final PortalWorkMetaService portalWorkMetaService;

    @Operation(summary = "포털 프레임 메타 Load",
            description = "데이터마트 자산은 원본과 본인 오버레이를 병합해 내려준다(본인 작업분 우선, 가린 값에 overridden 표시). "
                    + "본인이 올린 자산은 오버레이를 거치지 않고 그 자산의 메타를 그대로 내려주며 overridden 은 서지 않는다. "
                    + "어느 쪽인지는 화면이 알려 주지 않고 서버가 자산 출처로 판정한다. "
                    + "영상 축과 프레임 축을 함께 담으므로 원소마다 scope 로 축을 표시한다. "
                    + "촬영환경·프레임 설명·개인정보 판정 셋은 원장의 컬럼에서 조달해 키/값으로 바꿔 내리며 저장값이 없으면 자동 계산값으로 채워진다 "
                    + "— 값이 있다고 해서 사람이 고른 값이라는 뜻이 아니고 그 구분은 원소의 source 가 알려 준다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "병합된 메타"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 작업 대상이 아님(실재 여부를 드러내지 않는 응답)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "대상 프레임·영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 누락 신고가 열려 있는 원천 영상(본인 업로드 자산은 대상 아님)")
    })
    @GetMapping("/frames/{srcSn}/meta")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalMetaResponse> loadMeta(
            @Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(portalWorkMetaService.load(srcSn, requireActor(actor)));
    }

    @Operation(summary = "포털 프레임 메타 저장(오버레이 적재)",
            description = "저장처는 화면이 가르지 않고 서버가 자산 출처로 판정한다. 데이터마트 자산은 포털 전용 오버레이에만 적재하며 "
                    + "원본과 동결 스냅샷을 수정하지 않는다(단방향 — 데이터마트로 되돌아가지 않고 관제 통지·산출물 재생성을 일으키지 않는다). "
                    + "영상 축으로 표시된 원소는 프레임 참조를 비워 적재한다. "
                    + "표시 전용·기술 메타를 고치려는 요청은 400 이며 거부 메시지에 요청 키를 되돌려 싣지 않는다. "
                    + "★사용자가 직접 고르지 않은 항목은 보내지 않는다 — 자동 계산값을 그대로 되돌려 보내면 사람의 판정으로 승격되기 때문이다. "
                    + "서버도 같은 것을 독립으로 막는다(받은 값이 원본의 현재 유효값과 같으면 오버레이를 만들지 않고 있으면 지운다).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "적재 결과(저장 후 값)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "표시 전용·기술 메타 수정 시도이거나 값이 컬럼 폭을 넘음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 작업 대상이 아님(실재 여부를 드러내지 않는 응답)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "대상 프레임·영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 누락 신고가 열려 있는 원천 영상(본인 업로드 자산은 대상 아님)")
    })
    @PutMapping("/frames/{srcSn}/meta")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalMetaResponse> saveMeta(
            @Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
            @Valid @RequestBody PortalMetaUpdateRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(portalWorkMetaService.save(srcSn, requireActor(actor), request));
    }

    private String requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return actor.sub();
    }
}
