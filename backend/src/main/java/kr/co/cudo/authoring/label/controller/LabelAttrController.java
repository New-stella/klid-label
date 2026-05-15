package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelAttrRequest;
import kr.co.cudo.authoring.label.dto.LabelAttrResponse;
import kr.co.cudo.authoring.label.dto.LabelAttrValueResponse;
import kr.co.cudo.authoring.label.dto.LabelAttrValueUpsertRequest;
import kr.co.cudo.authoring.label.service.LabelAttrService;
import kr.co.cudo.authoring.label.service.LabelAttrValueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 라벨 속성 정의 + 객체별 속성값 API (CVAT-Like 라벨 풀 포팅 Phase 3).
 *
 * <p>권한 매트릭스:
 * <ul>
 *   <li>{@code GET /v1/manage/labels/{labelId}/attrs} — 인증된 사용자 모두 (SecurityConfig 매처).</li>
 *   <li>{@code POST/PUT/DELETE /v1/manage/labels/{labelId}/attrs[/{attrId}]} — REVIEWER 만.</li>
 *   <li>{@code GET /v1/labels/{lblSn}/attrs} — REVIEWER + WORKER + PORTAL_USER (메서드 {@code @PreAuthorize}).</li>
 *   <li>{@code PUT /v1/labels/{lblSn}/attrs} — REVIEWER + WORKER (메서드 {@code @PreAuthorize}).</li>
 * </ul>
 */
@Tag(name = "LabelAttr", description = "라벨별 속성 정의 및 객체별 속성값 — REVIEWER 가 정의, WORKER 가 값 입력.")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class LabelAttrController {

    private final LabelAttrService labelAttrService;
    private final LabelAttrValueService labelAttrValueService;

    // ────────────────────────────── 속성 정의 ──────────────────────────────

    @Operation(summary = "라벨의 활성 속성 정의 목록 조회 (인증된 사용자)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "라벨 없음")
    })
    @GetMapping("/v1/manage/labels/{labelId}/attrs")
    public ApiResponse<List<LabelAttrResponse>> listAttrs(@PathVariable Long labelId) {
        return ApiResponse.ok(labelAttrService.list(labelId));
    }

    @Operation(summary = "라벨 속성 정의 생성 (REVIEWER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "라벨 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동일 이름 중복")
    })
    @PostMapping("/v1/manage/labels/{labelId}/attrs")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LabelAttrResponse> createAttr(@PathVariable Long labelId,
                                                     @Valid @RequestBody LabelAttrRequest request,
                                                     @AuthenticationPrincipal TokenClaims actor) {
        String regId = actor != null ? actor.sub() : null;
        return ApiResponse.ok(labelAttrService.create(labelId, request, regId));
    }

    @Operation(summary = "라벨 속성 정의 수정 (REVIEWER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "라벨/속성 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동일 이름 중복")
    })
    @PutMapping("/v1/manage/labels/{labelId}/attrs/{attrId}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<LabelAttrResponse> updateAttr(@PathVariable Long labelId,
                                                     @PathVariable Long attrId,
                                                     @Valid @RequestBody LabelAttrRequest request,
                                                     @AuthenticationPrincipal TokenClaims actor) {
        String mdfcnId = actor != null ? actor.sub() : null;
        return ApiResponse.ok(labelAttrService.update(labelId, attrId, request, mdfcnId));
    }

    @Operation(summary = "라벨 속성 정의 삭제 — soft delete (REVIEWER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "라벨/속성 없음")
    })
    @DeleteMapping("/v1/manage/labels/{labelId}/attrs/{attrId}")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttr(@PathVariable Long labelId,
                           @PathVariable Long attrId,
                           @AuthenticationPrincipal TokenClaims actor) {
        String mdfcnId = actor != null ? actor.sub() : null;
        labelAttrService.delete(labelId, attrId, mdfcnId);
    }

    // ────────────────────────────── 객체별 속성값 ──────────────────────────────

    @Operation(summary = "객체별 속성값 조회 (REVIEWER+WORKER+PORTAL_USER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "라벨 객체 없음")
    })
    @GetMapping("/v1/labels/{lblSn}/attrs")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER', 'PORTAL_USER')")
    public ApiResponse<List<LabelAttrValueResponse>> listAttrValues(@PathVariable Long lblSn) {
        return ApiResponse.ok(labelAttrValueService.findByLblSn(lblSn));
    }

    @Operation(summary = "객체별 속성값 일괄 upsert (REVIEWER+WORKER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "라벨 객체 없음")
    })
    @PutMapping("/v1/labels/{lblSn}/attrs")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<Void> upsertAttrValues(@PathVariable Long lblSn,
                                              @Valid @RequestBody LabelAttrValueUpsertRequest request) {
        labelAttrValueService.upsert(lblSn, request.values());
        return ApiResponse.ok(null);
    }
}
