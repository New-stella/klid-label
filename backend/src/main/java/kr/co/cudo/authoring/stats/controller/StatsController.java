package kr.co.cudo.authoring.stats.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.stats.dto.DashboardSummaryResponse;
import kr.co.cudo.authoring.stats.dto.OverallStatSummaryResponse;
import kr.co.cudo.authoring.stats.dto.WorkerStatSummaryResponse;
import kr.co.cudo.authoring.stats.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCR-DASH-001 / SCR-STAT-001 / SCR-STAT-002 통계 API.
 *
 * <p>대시보드 요약 + 작업자 통계 + 전체 구축 현황 + 리포트 다운로드.
 * 작업자/전체 통계 집계는 placeholder — 실제 분포/표 데이터는 후속 Phase 에서 채운다.
 */
@Tag(name = "Stats", description = "통계·대시보드 — 메인 대시보드 KPI/이벤트 분포/내 작업 요약·작업자 통계·전체 구축 현황·리포트.")
@RestController
@RequestMapping("/v1/stats")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class StatsController {

    /** 입력 period allowlist — FE 와 동일 (Path/Command Injection 차단). */
    private static final String PERIOD_REGEX = "^(WEEK|MONTH|QUARTER|YEAR)$";

    private final StatsService statsService;

    @Operation(
            summary = "대시보드 요약 (REVIEWER/WORKER)",
            description = "처리 대기/완료/반려 KPI + 이미지·영상 누적 + 관제 카테고리 기준 이벤트 분포(9종) + 내 작업(WORKER 만 채움)"
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

    @Operation(
            summary = "작업자 통계 (REVIEWER/WORKER)",
            description = "FE WorkerStatPage 응답 — KPI 4 + 비율 2 + 일별 30일 + 월별 12개월. " +
                    "WORKER 는 본인 통계만 조회 가능 (CWE-639 IDOR 차단). " +
                    "REVIEWER 는 workerId 로 임의 작업자 통계 조회 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "workerId 형식 오류 (숫자 아님)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "WORKER 가 타인 통계 조회 시도")
    })
    @GetMapping("/worker")
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<WorkerStatSummaryResponse> worker(
            @AuthenticationPrincipal TokenClaims actor,
            @Parameter(description = "조회 대상 작업자 USER_NO (선택). REVIEWER 만 의미 있음.")
            @RequestParam(name = "workerId", required = false)
            @Pattern(regexp = "^[0-9]+$", message = "workerId 는 숫자만 허용") String workerId
    ) {
        Long parsed = (workerId == null) ? null : Long.parseLong(workerId);
        return ApiResponse.ok(statsService.getWorkerSummary(actor, parsed));
    }

    @Operation(
            summary = "전체 구축 현황 (REVIEWER 전용) — placeholder",
            description = "누적 이미지/영상 카드 + 처리 현황 5 카드 + 관제 카테고리 기준 이벤트 분포(9종) + 작업자별 표(빈 배열)."
    )
    @GetMapping("/overall")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<OverallStatSummaryResponse> overall() {
        return ApiResponse.ok(statsService.getOverallSummary());
    }

    @Operation(
            summary = "리포트 다운로드 (REVIEWER 전용) — placeholder CSV",
            description = "기간별 통계 CSV 다운로드. 현재는 헤더 행만 포함된 placeholder. period: WEEK|MONTH|QUARTER|YEAR."
    )
    @GetMapping(value = "/report", produces = "text/csv; charset=UTF-8")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<String> report(
            @RequestParam(name = "period", defaultValue = "WEEK")
            @Pattern(regexp = PERIOD_REGEX, message = "period 는 WEEK|MONTH|QUARTER|YEAR 만 허용") String period
    ) {
        // CWE-117 Header Injection 방어: period 는 위 @Pattern 으로 allowlist 검증 후만 헤더에 사용.
        String filename = "stats_report_" + period + ".csv";
        // BOM + 한글 안전 헤더 — Excel 호환.
        String body = "﻿month,labeled,reviewed,approvalRate\n";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }
}
