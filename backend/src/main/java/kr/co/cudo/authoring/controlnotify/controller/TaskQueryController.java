package kr.co.cudo.authoring.controlnotify.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskLabelsResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskMetaResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskSummaryResponse;
import kr.co.cudo.authoring.controlnotify.service.TaskQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 4 -- 관제서버 조회용 API.
 *
 * <p>통지 수신 후 상세 데이터 취득 목적. 조회 전용 (상태 변경 없음).
 * <p>{@code authoring.control-notify.enabled=true} 환경에서만 활성화.
 * <p>CWE-639 IDOR: @PreAuthorize 로 역할 검증 (REVIEWER/WORKER).
 */
@RestController
@RequestMapping("/v1/tasks")
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
@RequiredArgsConstructor
@Tag(name = "Task Query", description = "관제서버 조회용 API — 통지 수신 후 상세 데이터 취득")
@SecurityRequirement(name = "bearerAuth")
public class TaskQueryController {

    private final TaskQueryService taskQueryService;

    @Operation(
            summary = "영상별 요약 조회",
            description = "프레임 수, 라벨 수, 메타 수, 상태, 최종 수정일 반환."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/{rawSn}/summary")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<TaskSummaryResponse> getSummary(
            @Parameter(description = "raw 영상 PK", required = true) @PathVariable Long rawSn) {
        return ApiResponse.ok(taskQueryService.getSummary(rawSn));
    }

    @Operation(
            summary = "영상별 라벨 목록 조회",
            description = "프레임 단위 라벨 목록. frameIds 파라미터로 특정 프레임만 필터 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/{rawSn}/labels")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<List<TaskLabelsResponse>> getLabels(
            @Parameter(description = "raw 영상 PK", required = true) @PathVariable Long rawSn,
            @Parameter(description = "필터할 프레임 srcSn 목록 (생략 시 전체)")
            @RequestParam(required = false) List<Long> frameIds) {
        return ApiResponse.ok(taskQueryService.getLabels(rawSn, frameIds));
    }

    @Operation(
            summary = "영상별 메타데이터 조회",
            description = "영상에 연결된 메타 K/V 목록 반환."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/{rawSn}/meta")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<TaskMetaResponse> getMeta(
            @Parameter(description = "raw 영상 PK", required = true) @PathVariable Long rawSn) {
        return ApiResponse.ok(taskQueryService.getMeta(rawSn));
    }
}
