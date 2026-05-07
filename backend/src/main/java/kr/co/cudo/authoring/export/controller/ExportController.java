package kr.co.cudo.authoring.export.controller;

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
import kr.co.cudo.authoring.export.dto.ExportRequest;
import kr.co.cudo.authoring.export.dto.ExportStatusResponse;
import kr.co.cudo.authoring.export.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 * Phase 10 — 학습데이터셋 내보내기 API.
 * V1.4 정책: 데이터마트 검색·다운로드는 외부제공 시스템 책임. 본 API 는 NAS 디렉토리 생성까지만 담당.
 */
@Tag(name = "Export", description = "학습데이터셋 내보내기 — V1.4: NAS 디렉토리 생성까지만. 데이터마트 검색·다운로드는 외부 책임. REVIEWER 전용.")
@RestController
@RequestMapping("/v1/exports")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ExportController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ExportService service;

    /**
     * 데이터셋(내보내기 작업) 목록 페이징 — REVIEWER 의 /manage/datasets 화면용.
     */
    @Operation(
            summary = "데이터셋 목록 조회 (REVIEWER 전용)",
            description = "내보내기 작업의 페이징 목록을 등록일 최신순으로 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "size 한도 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/datasets")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Page<ExportStatusResponse>> listDatasets(
            @Parameter(description = "페이지 번호 (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (max 100)", example = "20") @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal TokenClaims actor) {
        if (size > MAX_PAGE_SIZE) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "size 한도 초과 (max=" + MAX_PAGE_SIZE + ")");
        }
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(service.listDatasets(pageable, actor));
    }

    /**
     * 내보내기 작업 등록. REVIEWER 만 호출 가능 (Service 이중 검증).
     */
    @Operation(
            summary = "내보내기 작업 등록 (REVIEWER 전용)",
            description = "지정한 프로젝트/필터 조건으로 학습데이터셋 내보내기 작업을 등록한다. 비동기 처리되며 status API로 진행 확인."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @PostMapping("/prepare")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ExportStatusResponse> prepare(@Valid @RequestBody ExportRequest req,
                                                     @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(service.prepareExport(req, actor));
    }

    /**
     * 내보내기 작업 상태 조회. REVIEWER 만.
     */
    @Operation(
            summary = "내보내기 작업 상태 조회 (REVIEWER 전용)",
            description = "내보내기 작업의 진행 상태와 결과 경로를 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "내보내기 작업 없음")
    })
    @GetMapping("/{exportSn}/status")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ExportStatusResponse> status(@Parameter(description = "내보내기 작업 PK", required = true, example = "1") @PathVariable Long exportSn,
                                                    @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(service.getStatus(exportSn, actor));
    }
}
