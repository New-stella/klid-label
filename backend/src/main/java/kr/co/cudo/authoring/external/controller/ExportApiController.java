package kr.co.cudo.authoring.external.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.external.dto.DatasetResponse;
import kr.co.cudo.authoring.external.service.ExportApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 4 — 외부 학습데이터 API.
 *
 * <p>외부(데이터마트/학습 시스템)가 영상 1건 단위로 학습데이터를 수집할 수 있도록 제공한다.
 * 인증: M2M 헤더 {@code X-M2M-Token} (scope=LEARNING_DATA). 인가: {@code M2M_LEARNING_DATA} 권한.
 *
 * <p>이전 SCR-EXPORT 화면용 {@code /v1/exports/**} API 는 V2 정책에 따라 폐기되었다.
 */
@Tag(name = "ExportApi (M2M)",
        description = "Phase 4 — 외부 학습데이터 API. X-M2M-Token (LEARNING_DATA scope) 필요. " +
                "응답은 영상 1건 + 메타(분리/병합) + RAW 프레임 라벨 1벌.")
@RestController
@RequestMapping("/v1/export-api")
@RequiredArgsConstructor
public class ExportApiController {

    private final ExportApiService service;

    /**
     * 영상 1건의 학습데이터 묶음 조회.
     *
     * @param rawSn LS_DATA_RAW.RAW_SN
     * @param merge true 면 RAW/DEID 메타를 단일 {@code meta} 로 병합 (RAW 우선). false (기본) 면 분리 응답.
     */
    @Operation(summary = "외부 학습데이터 조회",
            description = "영상 1건의 메타 + 라벨을 외부 학습데이터 시스템에 제공한다. " +
                    "merge=false (기본) 는 rawMeta/deidMeta 분리, merge=true 는 단일 meta(RAW 우선) 병합.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "M2M 토큰 누락/불일치 또는 scope 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/datasets/{rawSn}")
    @PreAuthorize("hasAuthority('M2M_LEARNING_DATA')")
    public ApiResponse<DatasetResponse> getDataset(
            @Parameter(description = "원시 영상 PK", required = true, example = "1")
            @PathVariable Long rawSn,
            @Parameter(description = "메타 병합 여부 (기본 false)", example = "false")
            @RequestParam(defaultValue = "false") boolean merge) {
        return ApiResponse.ok(service.build(rawSn, merge));
    }
}
