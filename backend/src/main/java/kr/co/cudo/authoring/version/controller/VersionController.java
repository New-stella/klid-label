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
import kr.co.cudo.authoring.version.dto.LabelDiffDto;
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
 *   <li>GET  /v1/frames/{srcSn}/versions             : 프레임 단위 버전 목록</li>
 *   <li>GET  /v1/versions/{commit}/diff?compareWith= : 두 커밋 비교</li>
 *   <li>POST /v1/versions/{commit}/rollback          : REVIEWER 전체 / WORKER 본인 배정 — 롤백</li>
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

    @Operation(
            summary = "커밋 간 diff 조회 (라벨 단위)",
            description = "두 커밋(from / to) 간 라벨 단위 변경 차이를 반환. compareWith는 from 커밋 SHA. "
                    + "ADDED/MODIFIED/REMOVED 로 분류된 라벨별 변경 사항을 배열로 반환한다. "
                    + "(파일 단위 메타가 필요한 내부 API 는 /diff/files 사용)"
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "커밋 없음")
    })
    @GetMapping("/versions/{commit}/diff")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<List<LabelDiffDto>> diff(@Parameter(description = "비교 대상(to) 커밋 SHA", required = true) @PathVariable("commit") String toSha,
                                                @Parameter(description = "기준(from) 커밋 SHA", required = true) @RequestParam("compareWith") String fromSha,
                                                @AuthenticationPrincipal TokenClaims actor) {
        DiffResponseDto dto = versionService.diff(fromSha, toSha, actor);
        return ApiResponse.ok(dto.labels());
    }

    /**
     * 파일 단위 diff 응답 (내부 API / 감사용). FE 작업이력 패널은 {@link #diff} 를 사용한다.
     * Phase 8 보강 (2026-05-19) — diff 엔드포인트 응답을 FE 가 기대하는 라벨 단위로 변경하면서
     * 기존 파일 단위 메타가 필요한 호출자(감사 로그, E2E 회귀 등) 를 위해 별도 경로로 노출.
     */
    @Operation(
            summary = "커밋 간 diff 조회 (파일 단위, 내부 API)",
            description = "두 커밋 간 파일 단위 변경(라벨 JSON 파일 path/additions/deletions) 을 반환. 내부 감사용."
    )
    @GetMapping("/versions/{commit}/diff/files")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<DiffResponseDto> diffFiles(@Parameter(description = "비교 대상(to) 커밋 SHA", required = true) @PathVariable("commit") String toSha,
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
