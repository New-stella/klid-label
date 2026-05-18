package kr.co.cudo.authoring.version.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
import kr.co.cudo.authoring.version.dto.RollbackRequest;
import kr.co.cudo.authoring.version.dto.VersionItem;
import kr.co.cudo.authoring.version.dto.VersionResponse;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.service.VersionService;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 8 — 버전관리(라벨 변경 이력) REST API.
 *
 * <ul>
 *   <li>GET  /v1/frames/{srcSn}/versions             : 프레임 단위 버전 목록 (정식 경로 — 2026-05-18 도입)</li>
 *   <li>GET  /v1/videos/{srcSn}/versions             : 위 엔드포인트의 deprecated alias (backward compat)</li>
 *   <li>GET  /v1/versions/{commit}/diff?compareWith= : 두 커밋 비교</li>
 *   <li>POST /v1/versions/{commit}/rollback          : REVIEWER 전체 / WORKER 본인 배정 — 롤백</li>
 * </ul>
 *
 * <p>경로 의미 정리(2026-05-18):
 * <ul>
 *   <li>{@code srcSn} 은 {@code LS_DATA_SRC.SRC_SN} (프레임 단위 PK) 이다.
 *   <li>기존 {@code /v1/videos/{srcSn}/versions} 는 path prefix({@code /v1/videos/{rawSn}/frames/...}) 와 의미가 어긋났다.
 *       정식 경로 {@code /v1/frames/{srcSn}/versions} 를 추가하고, 기존 경로는 호출자 마이그레이션을 위해
 *       deprecated alias 로 한시적으로 유지한다.
 * </ul>
 */
@Tag(name = "Version", description = "Gitea 기반 버전관리 — 라벨 변경 이력 / diff / 롤백. 롤백은 REVIEWER 전체 또는 WORKER 본인 배정 프레임.")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class VersionController {

    private final VersionService versionService;

    @Operation(
            summary = "프레임 버전 이력 조회",
            description = "프레임(srcSn = LS_DATA_SRC.SRC_SN) 단위로 라벨 변경 커밋 이력을 시간 역순으로 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    @GetMapping("/frames/{srcSn}/versions")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<List<VersionItem>> listVersions(@Parameter(description = "프레임 PK (LS_DATA_SRC.SRC_SN)", required = true, example = "1") @PathVariable Long srcSn,
                                                       @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(versionService.listVersions(srcSn, actor));
    }

    /**
     * Deprecated alias of {@link #listVersions(Long, TokenClaims)}.
     * 기존 호출자(FE/E2E)가 정식 경로 {@code /v1/frames/{srcSn}/versions} 로 이행할 때까지만 한시적으로 유지한다.
     * 동작은 정식 경로와 동일하다.
     */
    @Operation(
            summary = "[Deprecated] 프레임 버전 이력 조회 (기존 경로 alias)",
            description = "정식 경로 GET /v1/frames/{srcSn}/versions 와 동일. backward compat 용도로만 유지된다."
    )
    @Deprecated
    @GetMapping("/videos/{srcSn}/versions")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<List<VersionItem>> listVersionsLegacy(@Parameter(description = "프레임 PK (LS_DATA_SRC.SRC_SN)", required = true, example = "1") @PathVariable Long srcSn,
                                                             @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(versionService.listVersions(srcSn, actor));
    }

    @Operation(
            summary = "커밋 간 diff 조회",
            description = "두 커밋(from / to) 간 라벨 변경 차이를 반환. compareWith는 from 커밋 SHA."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "커밋 없음")
    })
    @GetMapping("/versions/{commit}/diff")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<DiffResponseDto> diff(@Parameter(description = "비교 대상(to) 커밋 SHA", required = true) @PathVariable("commit") String toSha,
                                              @Parameter(description = "기준(from) 커밋 SHA", required = true) @RequestParam("compareWith") String fromSha,
                                              @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(versionService.diff(fromSha, toSha, actor));
    }

    @Operation(
            summary = "특정 커밋으로 롤백 (REVIEWER 전체 / WORKER 본인 배정)",
            description = "지정 커밋의 라벨 상태로 되돌린다. 새 커밋이 생성되며 LS_LABEL_VERSION에 기록. "
                    + "REVIEWER는 모든 프레임, WORKER는 본인에게 배정된 프레임만 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 / 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "커밋/영상 없음")
    })
    @PostMapping("/versions/{commit}/rollback")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<VersionResponse.Item> rollback(@Parameter(description = "롤백 대상 커밋 SHA", required = true) @PathVariable("commit") String commitHash,
                                                       @Valid @RequestBody RollbackRequest req,
                                                       @AuthenticationPrincipal TokenClaims actor) {
        LsLabelVersion version = versionService.rollback(commitHash, req.srcSn(), actor);
        return ApiResponse.ok(VersionResponse.Item.from(version));
    }
}
