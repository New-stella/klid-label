package kr.co.cudo.authoring.batch.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.batch.dto.BatchStageSkipRequest;
import kr.co.cudo.authoring.batch.dto.BatchStageSkipResponse;
import kr.co.cudo.authoring.batch.service.BatchStageSkipService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배치 <b>작업 묶음</b> 수동 스킵/해제 API — REVIEWER 전용. [@design API-198] [@design API-200]
 *
 * <p>기다려도 성공하지 않는 작업(외부 VLM 벤더 장애 등) 때문에 영상 전체가 FAILED 로 고착되는 것을
 * 막기 위해, REVIEWER 가 시계열(VLM) 또는 오토라벨(AUTOLABEL) 묶음만 건너뛰고 나머지를 완주시킨다.
 * 스킵은 언제든 해제할 수 있으며(차단에는 되돌리는 길), 해제는 <b>표식만 지우고 작업을 실행하지
 * 않는다</b>.
 *
 * <p>★단위가 개별 단계가 아니라 묶음인 이유는 {@code BatchStageBundle} 참조 — 오토라벨은 AI 탐지 ·
 * AI 분할 · 트랙 보간이 한 벌이라 쪼개면 산출물끼리 어긋나고, 보간이 묶음 밖에 있으면 어떤 재수행에서도
 * 무조건 돌아 사람이 손댄 보간 라벨을 지운다.
 */
@Tag(name = "BatchStageSkip", description = "배치 작업 묶음 수동 스킵/해제 — REVIEWER 전용 (VLM/AUTOLABEL)")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Validated
public class BatchStageSkipController {

    private final BatchStageSkipService batchStageSkipService;

    @Operation(
            summary = "배치 작업 묶음 수동 스킵 (REVIEWER)",
            description = "지정한 작업 묶음(VLM = 시계열 · AUTOLABEL = AI 탐지·AI 분할·트랙 보간)을 이후 "
                    + "재기동에서 실행하지 않도록 표식을 남긴다. 오토라벨은 쪼개서 일부만 건너뛸 수 없다. "
                    + "사유는 필수이며, 허용 묶음 외·파생영상은 400 으로 거부된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "스킵 표식 적재"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "허용되지 않은 작업 묶음 / 사유 누락 / 파생영상"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @PostMapping("/{rawSn}/batch/stages/{stage}/skip")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<BatchStageSkipResponse> skipStage(
            @Parameter(description = "영상 단위 식별자", required = true, example = "12")
            @PathVariable @Min(value = 1, message = "rawSn 은 1 이상이어야 합니다.") Long rawSn,
            @Parameter(description = "건너뛸 작업 묶음 — VLM / AUTOLABEL", required = true, example = "VLM")
            @PathVariable String stage,
            @Valid @RequestBody BatchStageSkipRequest request) {
        return ApiResponse.ok(batchStageSkipService.skip(rawSn, stage, request.reason()));
    }

    @Operation(
            summary = "배치 작업 묶음 수동 스킵 해제 (REVIEWER)",
            description = "스킵 표식을 해제한다. <b>작업을 실행하지는 않으며</b>, 실제 실행은 재기동 API 가 담당한다. "
                    + "스킵 상태가 아니어도 204 (멱등)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "해제 완료(본문 없음)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "허용되지 않은 작업 묶음 / 파생영상"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @DeleteMapping("/{rawSn}/batch/stages/{stage}/skip")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearStageSkip(
            @Parameter(description = "영상 단위 식별자", required = true, example = "12")
            @PathVariable @Min(value = 1, message = "rawSn 은 1 이상이어야 합니다.") Long rawSn,
            @Parameter(description = "건너뛰기를 해제할 작업 묶음 — VLM / AUTOLABEL", required = true, example = "VLM")
            @PathVariable String stage) {
        batchStageSkipService.clearSkip(rawSn, stage);
    }
}
