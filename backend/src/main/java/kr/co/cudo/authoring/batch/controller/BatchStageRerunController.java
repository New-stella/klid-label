package kr.co.cudo.authoring.batch.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.batch.dto.BatchStageRerunResponse;
import kr.co.cudo.authoring.batch.service.BatchStageRerunService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 되돌린 <b>작업 묶음</b>의 지목 재수행 API — REVIEWER 전용. [@design API-201]
 *
 * <p>「문제가 생긴 곳부터 재시도한다」는 원칙에 따라 전체 재기동({@code /batch/retry})과 분리된
 * 요청이다. 되돌린 그 묶음을 지목해 <b>그 묶음만</b> 실행한다 — 묶음이 곧 범위라 요청이 범위를 고르지
 * 않는다(구 {@code scope} 요청 본문 폐기).
 *
 * <p>별도 sub-resource 로 둔 이유는 이 저장소의 「같은 URL 에 쿼리 파라미터로 행위 분기 금지」 규약이다
 * (전례: 프레임 비식별 이미지 전용 엔드포인트).
 */
@Tag(name = "BatchStageRerun", description = "되돌린 작업 묶음 재수행 — REVIEWER 전용 (VLM/AUTOLABEL)")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Validated
public class BatchStageRerunController {

    private final BatchStageRerunService batchStageRerunService;

    @Operation(
            summary = "되돌린 작업 묶음 재수행 (REVIEWER)",
            description = "건너뛰기를 되돌린 작업 묶음(VLM / AUTOLABEL)을 다시 수행한다. 묶음이 곧 범위라 "
                    + "요청은 범위를 고르지 않으며, 되돌린 그 묶음만 수행하고 다른 묶음은 건드리지 않는다. "
                    + "오토라벨을 재수행하면 트랙 보간도 함께 다시 만들어져 사람이 손댄 보간 라벨이 새로 계산된 "
                    + "것으로 바뀐다 — 오토라벨을 통째로 다시 만드는 선택이므로 예상되는 결과이며 화면이 고르는 "
                    + "시점에 알린다. 대상 묶음은 그 영상에서 실제로 되돌린 묶음만 수락하며 그 외에는 400 이다. "
                    + "상태 선점까지만 요청 안에서 처리하고 실행은 비동기이므로 200 은 접수 사실이지 완료가 아니다. "
                    + "재수행이 실패해도 영상 상태는 선점 직전으로 되돌아가며 자동 재시도 큐에 들어가지 않는다. "
                    + "한 번이라도 검수가 완료된 영상은 400 으로 거부된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재수행 접수 — 완료를 뜻하지 않는다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "되돌린 묶음이 아님 / 지원하지 않는 묶음 / 한번이라도 검수가 완료된 영상"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "완주 상태가 아님 / 검수 소유 상태 / 이미 재수행이 진행 중"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "재수행 접수 용량 초과 — 잠시 후 재시도")
    })
    @PostMapping("/{rawSn}/batch/stages/{stage}/rerun")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<BatchStageRerunResponse> rerunStage(
            @Parameter(description = "영상 단위 식별자", required = true, example = "1")
            @PathVariable @Min(value = 1, message = "rawSn 은 1 이상이어야 합니다.") Long rawSn,
            @Parameter(description = "재수행할 작업 묶음 — VLM / AUTOLABEL", required = true, example = "AUTOLABEL")
            @PathVariable String stage) {
        return ApiResponse.ok(batchStageRerunService.rerun(rawSn, stage));
    }
}
