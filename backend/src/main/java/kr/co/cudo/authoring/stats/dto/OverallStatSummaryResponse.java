package kr.co.cudo.authoring.stats.dto;

import java.util.List;

/**
 * SCR-STAT-002 전체 구축 현황 응답 (REVIEWER 전용 placeholder).
 *
 * <p>FE {@code OverallStatSummary} 와 1:1 매칭 (frontend/src/features/stat/types.ts).
 * 작업자별 집계는 후속 Phase — 현재는 0 / 빈 배열 반환.
 */
public record OverallStatSummaryResponse(
        long cumulativeImageCount,
        long cumulativeVideoCount,
        Processing processing,
        List<EventDistributionItem> eventDistribution,
        List<WorkerRow> workers
) {

    public record Processing(
            long pending,
            long inProgress,
            long reviewPending,
            long approved,
            long rejected
    ) {
        public static Processing empty() {
            return new Processing(0, 0, 0, 0, 0);
        }
    }

    public record WorkerRow(
            long userId,
            String name,
            long labeled,
            long reviewed,
            double approvalRate
    ) {
    }
}
