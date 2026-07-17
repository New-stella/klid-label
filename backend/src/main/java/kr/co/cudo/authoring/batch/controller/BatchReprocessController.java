package kr.co.cudo.authoring.batch.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.batch.dto.BatchReprocessResponse;
import kr.co.cudo.authoring.batch.service.BatchReprocessService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배치 재처리(FAILED 복구) API (B3).
 *
 * <p>배치 파이프라인이 FAILED 로 고착된 영상을 REVIEWER 가 수동으로 재기동한다.
 * FAILED 가 아닌 영상은 409 로 거부된다.
 */
@Tag(name = "BatchReprocess", description = "배치 재처리 — REVIEWER 전용. FAILED 영상 재기동.")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Validated
public class BatchReprocessController {

    private final BatchReprocessService batchReprocessService;

    @Operation(
            summary = "배치 재처리 (REVIEWER)",
            description = "배치가 실패(FAILED)한 영상의 파이프라인을 수동 재기동한다. " +
                    "FAILED 가 아닌 영상은 409 로 거부된다. 자동 재시도 큐와 동일한 재실행 경로를 재사용한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재기동 성공 — 결과 단계 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 입력(rawSn)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "FAILED 상태가 아닌 영상")
    })
    @PostMapping("/{rawSn}/batch/retry")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<BatchReprocessResponse> retryBatch(
            @Parameter(description = "영상 단위 식별자", required = true, example = "1")
            @PathVariable @Min(value = 1, message = "rawSn 은 1 이상이어야 합니다.") Long rawSn) {
        return ApiResponse.ok(batchReprocessService.retry(rawSn));
    }
}
