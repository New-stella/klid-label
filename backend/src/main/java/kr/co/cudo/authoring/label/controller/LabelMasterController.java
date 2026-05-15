package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelMasterRequest;
import kr.co.cudo.authoring.label.dto.LabelMasterResponse;
import kr.co.cudo.authoring.label.service.LabelMasterService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 라벨 마스터 관리 API (CVAT-Like 라벨 풀 포팅 Phase 1).
 *
 * <p>권한:
 * <ul>
 *   <li>GET — REVIEWER + WORKER + PORTAL_USER (인증된 사용자 모두). SecurityConfig 에서 매처로 처리.</li>
 *   <li>POST / PUT / DELETE — REVIEWER 만 (메서드 {@code @PreAuthorize} + SecurityConfig 의 {@code /v1/manage/**} 매처).</li>
 * </ul>
 */
@Tag(name = "LabelMaster", description = "프로젝트 단위 라벨 마스터 CRUD — REVIEWER 가 관리, 조회는 WORKER/PORTAL_USER 도 가능.")
@RestController
@RequestMapping("/v1/manage/labels")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class LabelMasterController {

    private final LabelMasterService labelMasterService;

    @Operation(summary = "프로젝트의 활성 라벨 마스터 목록 조회 (인증된 사용자)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @GetMapping
    public ApiResponse<List<LabelMasterResponse>> list(
            @Parameter(description = "프로젝트 ID", required = true, example = "1")
            @RequestParam("pjtId") @NotNull Long pjtId) {
        return ApiResponse.ok(labelMasterService.list(pjtId));
    }

    @Operation(summary = "라벨 마스터 생성 (REVIEWER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동일 이름 중복")
    })
    @PostMapping
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LabelMasterResponse> create(@Valid @RequestBody LabelMasterRequest request,
                                                   @AuthenticationPrincipal TokenClaims actor) {
        String regId = actor != null ? actor.sub() : null;
        return ApiResponse.ok(labelMasterService.create(request, regId));
    }

    @Operation(summary = "라벨 마스터 수정 (REVIEWER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "라벨 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동일 이름 중복")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<LabelMasterResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody LabelMasterRequest request,
                                                   @AuthenticationPrincipal TokenClaims actor) {
        String mdfcnId = actor != null ? actor.sub() : null;
        return ApiResponse.ok(labelMasterService.update(id, request, mdfcnId));
    }

    @Operation(summary = "라벨 마스터 삭제 — soft delete (REVIEWER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "라벨 없음")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal TokenClaims actor) {
        String mdfcnId = actor != null ? actor.sub() : null;
        labelMasterService.delete(id, mdfcnId);
    }
}
