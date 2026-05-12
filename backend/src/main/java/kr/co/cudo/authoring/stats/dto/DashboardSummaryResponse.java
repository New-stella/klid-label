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
        /**
         * 영상(LS_DATA_RAW) 단위 이벤트 6종 분포 — "영상 데이터 개수" 카드용.
         * 후방 호환을 위해 필드명을 유지한다.
         */
        List<EventDistributionItem> eventDistribution,
        /**
         * 프레임(LS_DATA_SRC) 단위 이벤트 6종 분포 — "이미지 데이터 개수" 카드용.
         * 영상 1건당 N프레임이 모두 합산되므로 eventDistribution 보다 일반적으로 크다.
         */
        List<EventDistributionItem> imageDistribution,
        MyTaskBreakdown myTask,
        List<NoticeItem> notices
) {
}
