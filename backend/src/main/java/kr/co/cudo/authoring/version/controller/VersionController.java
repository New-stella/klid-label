package kr.co.cudo.authoring.version.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.version.dto.CommitRequest;
import kr.co.cudo.authoring.version.dto.CommitResponseDto;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
import kr.co.cudo.authoring.version.dto.LabelDiffDto;
import kr.co.cudo.authoring.version.dto.RollbackRequest;
import kr.co.cudo.authoring.version.dto.VersionItem;
import kr.co.cudo.authoring.version.dto.VersionResponse;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.service.VersionService;
import lombok.extern.slf4j.Slf4j;

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
 * Phase 5 — 버전관리(라벨 변경 이력) REST API (DB 스냅샷 기반).
 *
 * <ul>
 *   <li>POST /v1/frames/{srcSn}/commit                 : 현재 라벨 상태를 새 버전으로 저장</li>
 *   <li>GET  /v1/frames/{srcSn}/versions               : 프레임 단위 버전 목록</li>
 *   <li>GET  /v1/versions/{version}/diff?compareWith=  : 두 버전(versionHash) 라벨 단위 비교</li>
 *   <li>POST /v1/versions/{version}/rollback           : REVIEWER 전체 / WORKER 본인 배정 — 롤백</li>
 * </ul>
 *
 * <p>식별자는 라벨 스냅샷의 SHA-256(versionHash). FE 와이어 포맷 호환을 위해 응답 필드명은
 * {@code commitSha} 를 유지하나 의미는 versionHash 다.
 */
@Slf4j
@Tag(name = "Version", description = "DB 스냅샷 기반 버전관리 — 라벨 저장 / 변경 이력 / diff / 롤백. 롤백은 REVIEWER 전체 또는 WORKER 본인 배정 프레임.")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class VersionController {

    private final VersionService versionService;
    private final LabelService labelService;
    private final ObjectMapper objectMapper;

    @Operation(
            summary = "현재 라벨 상태를 새 버전으로 저장",
            description = "프레임의 현재 라벨 상태를 DB 스냅샷으로 저장한다. "
                    + "FE 라벨링 화면에서 명시적 '커밋' 버튼 클릭 시 호출. "
                    + "PORTAL 채널은 버전관리 미제공 → 403."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 / PORTAL 채널"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "비식별 재처리 중")
    })
    @PostMapping("/frames/{srcSn}/commit")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<CommitResponseDto> commit(
            @Parameter(description = "프레임 PK (LS_DATA_SRC.SRC_SN)", required = true, example = "1")
            @PathVariable Long srcSn,
            @RequestBody(required = false) CommitRequest req,
            @AuthenticationPrincipal TokenClaims actor) {

        // PORTAL 채널은 버전관리(커밋) 미제공
        if (!VersionService.isCommittable(actor)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "포털 채널은 커밋 기능을 사용할 수 없습니다.");
        }

        // 현재 라벨 상태 조회 → JSON 직렬화
        LabelResponse labelSnapshot = labelService.getByFrame(srcSn, actor);
        String labelsJson;
        try {
            labelsJson = objectMapper.writeValueAsString(labelSnapshot);
        } catch (Exception e) {
            log.error("[Version] labels JSON serialize failed srcSn={}", srcSn, e);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "라벨 직렬화에 실패했습니다.");
        }

        // DB 스냅샷 저장 → versionHash 반환
        String versionHash = versionService.commit(srcSn, labelsJson, actor);
        return ApiResponse.ok(CommitResponseDto.of(versionHash));
    }

    @Operation(
            summary = "프레임 버전 이력 조회",
            description = "프레임(srcSn = LS_DATA_SRC.SRC_SN) 단위로 라벨 변경 버전 이력을 시간 역순으로 반환한다."
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
            summary = "버전 간 diff 조회 (라벨 단위)",
            description = "두 버전(from / to) 간 라벨 단위 변경 차이를 반환. compareWith는 from 버전 해시(versionHash). "
                    + "ADDED/MODIFIED/REMOVED 로 분류된 라벨별 변경 사항을 배열로 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "버전 없음")
    })
    @GetMapping("/versions/{version}/diff")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<List<LabelDiffDto>> diff(@Parameter(description = "비교 대상(to) 버전 해시", required = true) @PathVariable("version") String toHash,
                                                @Parameter(description = "기준(from) 버전 해시", required = true) @RequestParam("compareWith") String fromHash,
                                                @AuthenticationPrincipal TokenClaims actor) {
        DiffResponseDto dto = versionService.diff(fromHash, toHash, actor);
        return ApiResponse.ok(dto.labels());
    }

    @Operation(
            summary = "특정 버전으로 롤백 (REVIEWER 전체 / WORKER 본인 배정)",
            description = "지정 버전의 라벨 스냅샷으로 되돌린다. 새 active 버전이 생성되며 LS_LABEL_VERSION에 기록. "
                    + "REVIEWER는 모든 프레임, WORKER는 본인에게 배정된 프레임만 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 / 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "버전/영상 없음")
    })
    @PostMapping("/versions/{version}/rollback")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<VersionResponse.Item> rollback(@Parameter(description = "롤백 대상 버전 해시", required = true) @PathVariable("version") String versionHash,
                                                       @Valid @RequestBody RollbackRequest req,
                                                       @AuthenticationPrincipal TokenClaims actor) {
        LsLabelVersion version = versionService.rollback(versionHash, req.srcSn(), actor);
        return ApiResponse.ok(VersionResponse.Item.from(version));
    }
}
