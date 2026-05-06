package kr.co.cudo.authoring.batch.scheduler.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.batch.scheduler.SchedulerHealthService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Quartz 스케줄러 헬스체크 엔드포인트.
 * - REVIEWER 전용. 운영 환경에서는 actuator 와 별개로 운영자가 빠르게 상태를 확인하기 위함.
 */
@Tag(name = "Scheduler Health", description = "Quartz 스케줄러 헬스체크 — REVIEWER 전용 운영 점검 채널.")
@RestController
@RequestMapping("/v1/system/scheduler")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class SchedulerHealthController {

    private final SchedulerHealthService schedulerHealthService;

    @Operation(
            summary = "Quartz 스케줄러 상태 조회",
            description = "스케줄러 실행 상태, 활성 트리거 수, 최근 실행 시각 등을 반환한다. REVIEWER 전용."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/health")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(schedulerHealthService.getHealth());
    }
}
