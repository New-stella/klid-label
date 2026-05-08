package kr.co.cudo.authoring.stats.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.stats.dto.DashboardSummaryResponse;
import kr.co.cudo.authoring.stats.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCR-DASH-001 메인 대시보드 통계 API.
 *
 * <p>FE {@code DashboardPage} 의 {@code GET /api/v1/stats/summary} 호출에 대응.
 * REVIEWER / WORKER 모두 접근 가능. (PORTAL_USER 는 차단)
 *
 * <p>응답 데이터가 비어있는 환경에서도 모든 카운트는 0, 배열은 빈 배열로 안전하게 반환된다.
 */
@Tag(name = "Stats", description = "통계·대시보드 — 메인 대시보드 KPI/이벤트 분포/내 작업 요약. REVIEWER/WORKER.")
@RestController
@RequestMapping("/v1/stats")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class StatsController {

    private final StatsService statsService;

    @Operation(
            summary = "대시보드 요약 (REVIEWER/WORKER)",
            description = "처리 대기/완료/반려 KPI + 이미지·영상 누적 + 6종 이벤트 분포 + 내 작업(WORKER 만 채움)"
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 (PORTAL_USER 등)")
    })
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<DashboardSummaryResponse> summary(@AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(statsService.getSummary(actor));
    }
}
