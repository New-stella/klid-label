package kr.co.cudo.authoring.stats.dto;

import java.util.List;

/**
 * SCR-STAT-001 작업자 통계 응답 (placeholder).
 *
 * <p>FE {@code WorkerStatSummary} 와 1:1 매칭 (frontend/src/features/stat/types.ts).
 * 데이터 집계 로직은 후속 Phase 에서 채운다 — 현재는 모든 카운트 0 / 빈 배열 반환.
 */
public record WorkerStatSummaryResponse(
        long totalLabeled,
        long totalReviewed,
        double approvalRate,
        long averageElapsedSec,
        List<DailyCompletion> dailyCompletion,
        List<EventDistributionItem> eventDistribution,
        List<MonthlyRow> monthly
) {

    public record DailyCompletion(String date, long count) {
    }

    public record MonthlyRow(String month, long labeled, long reviewed, double approvalRate) {
    }
}
