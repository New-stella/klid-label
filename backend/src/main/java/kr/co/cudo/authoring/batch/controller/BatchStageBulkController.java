package kr.co.cudo.authoring.batch.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.batch.dto.BatchStageBulkRequest;
import kr.co.cudo.authoring.batch.dto.BatchStageBulkResponse;
import kr.co.cudo.authoring.batch.dto.BatchStageSkipBulkRequest;
import kr.co.cudo.authoring.batch.service.BatchStageBulkService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작업 묶음 <b>일괄</b> 스킵 · 해제 · 재수행 API — REVIEWER 전용.
 * [@design API-212] [@design API-213] [@design API-214]
 *
 * <p>외부 시계열 분석 벤더 연동이 확정되기 전 구간의 운영은 「사람이 사유를 남기고 누르는 단계 스킵」으로
 * 처리한다. 단건 축은 이미 있으므로 여기에 <b>일괄 축</b>을 더한다 — 스킵만 일괄이면 벤더 연동 시점에
 * 수천 건을 손으로 되돌려야 하므로 <b>해제·재수행도 함께</b> 일괄로 둔다.
 *
 * <h3>★일괄 축은 시계열({@code VLM}) 묶음만 받는다</h3>
 * <p>오토라벨은 산출물이 라벨이라 대량으로 건너뛸 수 있게 열면 산출물 품질 축이 조용히 느슨해진다.
 * <b>단건 경로는 종전대로 두 묶음을 모두 받는다</b>({@link BatchStageSkipController} ·
 * {@link BatchStageRerunController}).
 *
 * <h3>경로가 단건과 겹치지 않는 이유</h3>
 * <p>단건은 {@code /v1/videos/{rawSn}/batch/stages/{stage}/…}(7 세그먼트), 일괄은
 * {@code /v1/videos/batch/stages/{stage}/…}(6 세그먼트)라 <b>세그먼트 수가 달라</b> 같은 URL 이 둘 다에
 * 매칭될 수 없다({@code {rawSn}} 자리에 리터럴 {@code batch} 가 오는 형태가 아니다). 기존
 * {@code POST /v1/videos/batch/retry} 가 같은 구조로 이미 공존한다 — 이 성질은 테스트로 고정한다.
 *
 * <h3>해제가 DELETE 인 이유</h3>
 * <p>스킵 표식이라는 하위 리소스를 지우는 요청이라, 같은 URL 에 쿼리 파라미터로 행위를 분기하지 않는다는
 * 이 저장소 규약을 따른다. <b>대상 목록을 본문으로 받는 DELETE</b> 이므로 중간 경로가 본문을 버리는
 * 구성에서는 목록이 전달되지 않는다 — 그 경우 "전건 해제"로 흐르지 않고 400 이다(본문 필수).
 */
@Tag(name = "BatchStageBulk",
        description = "작업 묶음 일괄 스킵/해제/재수행 — REVIEWER 전용 (시계열 VLM 묶음만)")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Validated
public class BatchStageBulkController {

    private static final String STAGE_DESC =
            "대상 작업 묶음 — 일괄 축은 시계열(VLM)만 받는다. 그 외 값은 400";

    private final BatchStageBulkService batchStageBulkService;

    /**
     * 작업 묶음 일괄 스킵. [@design API-212]
     *
     * <p>사유는 필수이며 <b>요청당 하나</b>를 받아 대상 전건에 같은 값으로 기록한다. 부분 성공이며
     * 한 건도 처리되지 못해도 200 이다(판정은 {@code results}).
     */
    @Operation(
            summary = "배치 작업 묶음 일괄 스킵 (REVIEWER)",
            description = "여러 영상의 시계열(VLM) 묶음을 한 번에 건너뛴다. 단건 스킵과 판정·기록 규칙이 같고 "
                    + "대상만 목록으로 받는다. 사유는 필수이며 요청당 하나를 받아 대상 전건에 같은 값으로 배치 "
                    + "이력에 기록된다. 오토라벨 묶음은 받지 않는다(400) — 산출물이 라벨이라 대량으로 건너뛸 수 "
                    + "있게 열지 않았다. 되는 것만 처리하고 거부분은 건별 사유로 반환하며, 한 건도 처리되지 "
                    + "못해도 200 이다(판정은 results). 1~100건(각 원소 1 이상, 중복은 1건 취급, 요청 순서 보존)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 완료 — 건별 결과 반환(부분 성공 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "빈 목록 / 상한(100건) 초과 / 1 미만 식별자 / 사유 누락 / 지원하지 않는 작업 묶음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @PostMapping("/batch/stages/{stage}/skip")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<BatchStageBulkResponse> skipBatchStageBulk(
            @Parameter(description = STAGE_DESC, required = true, example = "VLM")
            @PathVariable String stage,
            @Valid @RequestBody BatchStageSkipBulkRequest request) {
        return ApiResponse.ok(batchStageBulkService.skipAll(stage, request));
    }

    /**
     * 작업 묶음 일괄 스킵 해제. [@design API-213]
     *
     * <p>해제는 표식을 지우는 것이 아니라 <b>해제 표식을 덧붙이는 것</b>이라 누가 언제 되돌렸는지가 함께
     * 남는다(단건과 같다). <b>작업을 실행하지는 않으며</b> 실제 실행은 일괄 재수행이 담당한다.
     */
    @Operation(
            summary = "배치 작업 묶음 일괄 스킵 해제 (REVIEWER)",
            description = "여러 영상의 시계열(VLM) 묶음 스킵을 한 번에 해제한다. 해제는 표식을 지우는 것이 아니라 "
                    + "해제 표식을 덧붙이는 것이라 누가 언제 되돌렸는지가 함께 남는다(단건과 같다). 해제만으로는 "
                    + "시계열이 채워지지 않으며 실제 재수행은 일괄 재수행 API 가 담당한다. 대상 목록을 본문으로 "
                    + "받는 삭제 요청이므로 중간 경로가 본문을 버리는 구성에서는 목록이 전달되지 않는다(그 경우 400). "
                    + "부분 성공이며 한 건도 처리되지 못해도 200 이다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 완료 — 건별 결과 반환(부분 성공 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "빈 목록 / 본문 없음 / 상한(100건) 초과 / 1 미만 식별자 / 지원하지 않는 작업 묶음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @DeleteMapping("/batch/stages/{stage}/skip")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<BatchStageBulkResponse> clearBatchStageSkipBulk(
            @Parameter(description = STAGE_DESC, required = true, example = "VLM")
            @PathVariable String stage,
            @Valid @RequestBody BatchStageBulkRequest request) {
        return ApiResponse.ok(batchStageBulkService.clearAll(stage, request));
    }

    /**
     * 되돌린 작업 묶음 일괄 재수행. [@design API-214]
     *
     * <p>건별 결과는 <b>접수 여부</b>이지 파이프라인 완료가 아니다(실행은 비동기). 수락 조건은 단건과
     * 같으며 — 그 영상에서 실제로 되돌린 묶음인가 하나다 — 검수 승인 이력이 있는 영상도 시계열 묶음은
     * 대상이 된다.
     */
    @Operation(
            summary = "되돌린 배치 작업 묶음 일괄 재수행 (REVIEWER)",
            description = "되돌린 시계열(VLM) 묶음을 여러 영상에 대해 한 번에 다시 수행한다. 수락 조건은 그 영상에서 "
                    + "실제로 되돌린 묶음인가 하나이며 단건 재수행과 같은 판정을 쓴다. 검수 승인 이력이 있는 영상도 "
                    + "이 경로에서는 대상이 된다 — 시계열 재수행은 확정된 라벨을 되돌리지 않고 메타만 더하며, 들어온 "
                    + "서술이 실제로 달라졌을 때만 재검수·산출물 재생성·관제 재통지가 걸린다(같은 값이면 no-op). "
                    + "건별 결과는 접수 여부이지 완료가 아니다(실행은 비동기). 부분 성공이며 한 건도 접수되지 못해도 200."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 완료 — 건별 접수 결과 반환(부분 성공 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "빈 목록 / 상한(100건) 초과 / 1 미만 식별자 / 지원하지 않는 작업 묶음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @PostMapping("/batch/stages/{stage}/rerun")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<BatchStageBulkResponse> rerunBatchStageBulk(
            @Parameter(description = STAGE_DESC, required = true, example = "VLM")
            @PathVariable String stage,
            @Valid @RequestBody BatchStageBulkRequest request) {
        return ApiResponse.ok(batchStageBulkService.rerunAll(stage, request));
    }
}
