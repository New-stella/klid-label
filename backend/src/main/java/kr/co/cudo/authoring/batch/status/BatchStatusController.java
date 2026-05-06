package kr.co.cudo.authoring.batch.status;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.batch.dto.BatchStageProgress;
import kr.co.cudo.authoring.batch.dto.BatchStatusResponse;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 배치 단계 진행 상태 조회 API (Phase 5).
 *  - REVIEWER 또는 WORKER 권한자 접근 가능.
 *  - 최근 N건 (기본 50, 최대 500) — limit 검증.
 */
@Tag(name = "Batch Status", description = "배치 파이프라인 진행 상태 — REVIEWER/WORKER 조회 가능. 최근 N건 (max 500).")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/batch")
@SecurityRequirement(name = "bearerAuth")
public class BatchStatusController {

    private final BatchStatusService statusService;

    @Operation(
            summary = "최근 배치 진행 상태 조회",
            description = "최근 limit건의 단계별 진행 상태(FFmpeg/비식별/YOLO/SAM2/VLM)를 반환. limit는 1~500."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "limit 범위 위반"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER/WORKER 권한 없음")
    })
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<BatchStatusResponse> recent(
            @Parameter(description = "조회할 최대 건수 (1~500)", example = "50")
            @RequestParam(name = "limit", defaultValue = "50")
            @Min(1) @Max(500) int limit) {
        List<BatchStageProgress> items = statusService.recent(limit);
        return ApiResponse.ok(new BatchStatusResponse(items));
    }
}
