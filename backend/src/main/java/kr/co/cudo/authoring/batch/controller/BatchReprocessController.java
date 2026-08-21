package kr.co.cudo.authoring.batch.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.batch.dto.BatchBulkRetryRequest;
import kr.co.cudo.authoring.batch.dto.BatchBulkRetryResponse;
import kr.co.cudo.authoring.batch.dto.BatchReprocessResponse;
import kr.co.cudo.authoring.batch.service.BatchBulkRetryService;
import kr.co.cudo.authoring.batch.service.BatchReprocessService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배치 재처리(FAILED 복구) API (B3).
 *
 * <p>배치 파이프라인이 FAILED 로 고착된 영상을 REVIEWER 가 수동으로 재기동한다.
 * FAILED 가 아닌 영상은 409 로 거부된다.
 */
@Tag(name = "BatchReprocess", description = "배치 재처리 — REVIEWER 전용. 실패(FAILED) 영상 재기동.")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Validated
public class BatchReprocessController {

    private final BatchReprocessService batchReprocessService;
    private final BatchBulkRetryService batchBulkRetryService;

    @Operation(
            summary = "배치 재처리 접수 (REVIEWER)",
            description = "배치가 실패(FAILED)한 영상의 파이프라인을 수동 재기동한다. 이미 성공한 단계는 다시 "
                    + "수행하지 않는다. 상태를 선점하는 것까지만 요청 안에서 처리하고 실제 실행은 비동기로 넘긴다 — "
                    + "200 은 접수 사실이지 파이프라인이 끝났다는 뜻이 아니며, 진행 상황은 영상 상세 조회의 "
                    + "단계 표시로 확인한다. 실패 상태가 아닌 영상은 409 로 거부된다. 건너뛰기를 해제해 "
                    + "다시 수행하는 것은 단계 지목 재수행 API(/batch/stages/{stage}/rerun)가 담당한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재기동 접수 — 접수 시점 단계 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 입력(rawSn < 1 등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "FAILED 가 아닌 영상 / 검수 소유 상태 / 이미 진행 중"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "재기동 접수 용량 초과 — 잠시 후 재시도")
    })
    @PostMapping("/{rawSn}/batch/retry")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<BatchReprocessResponse> retryBatch(
            @Parameter(description = "영상 단위 식별자", required = true, example = "1")
            @PathVariable @Min(value = 1, message = "rawSn 은 1 이상이어야 합니다.") Long rawSn) {
        return ApiResponse.ok(batchReprocessService.retry(rawSn));
    }

    /**
     * 배치 <b>일괄</b> 재시작. [@design API-199]
     *
     * <p>부분 성공 — 되는 것만 재기동하고 거부분은 건별 사유로 돌려준다. <b>한 건도 성공하지 못해도
     * 200</b> 이며 판정은 응답의 {@code results} 로 한다(단건 경로의 409/404 를 목록 전체로 승격하지
     * 않는다). 각 건은 단건 재기동과 같은 원자 클레임을 그대로 탄다.
     *
     * <p><b>건별 결과는 재기동을 접수했는지 여부이지 파이프라인이 끝났다는 뜻이 아니다</b> — 실행은
     * 비동기이며 진행 상황은 각 영상 상세의 단계 표시로 확인한다.
     */
    @Operation(
            summary = "배치 일괄 재시작 접수 (REVIEWER)",
            description = "여러 영상의 배치를 한 번에 재기동한다. 되는 것만 재기동하고 거부분은 건별 사유로 반환한다. "
                    + "건별 결과는 재기동을 접수했는지 여부이지 파이프라인이 끝났다는 뜻이 아니다(실행은 비동기). "
                    + "한 건도 성공하지 못해도 200 이며, 판정은 응답의 results 로 한다. 1~100건(각 원소 1 이상, 중복은 1건 취급)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 완료 — 건별 접수 결과 반환(부분 성공 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "빈 목록 / 상한(100건) 초과 / 1 미만 식별자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @PostMapping("/batch/retry")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<BatchBulkRetryResponse> retryBatchBulk(
            @Valid @RequestBody BatchBulkRetryRequest request) {
        return ApiResponse.ok(batchBulkRetryService.retryAll(request));
    }
}
