package kr.co.cudo.authoring.stats.dto;

import java.util.List;

/**
 * SCR-DASH-001 메인 대시보드 요약 응답.
 *
 * <p>FE {@code DashboardSummary} 타입과 1:1 매칭 (frontend/src/features/dashboard/types.ts).
 * 데이터가 비어있는 환경에서도 모든 필드는 0 또는 빈 배열을 반환한다 (null 금지).
 *
 * <p>최근 완료 영상 목록은 본 응답에 포함하지 않는다 — FE 가 별도로 {@code GET /v1/videos?sort=...}
 * 페이징 API 를 호출한다.
 */
public record DashboardSummaryResponse(
        long pendingCount,
        long completedCount,
        long myTaskCount,
        long rejectedCount,
        long cumulativeImageCount,
        long cumulativeVideoCount,
        List<EventDistributionItem> eventDistribution,
        MyTaskBreakdown myTask,
        List<NoticeItem> notices
) {
}
