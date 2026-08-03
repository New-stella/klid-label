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
        List<WorkerRow> workers,
        /**
         * 검수 승인(APPROVED) 영상에 속한 프레임 누적 건수 — "이미지 학습데이터" 카드용.
         * 항상 {@code approvedImageCount <= cumulativeImageCount}.
         */
        long approvedImageCount,
        /**
         * 검수 승인(APPROVED) 영상 누적 건수 — "영상 학습데이터" 카드용.
         * {@link Processing#approved()} 와 동일 원천을 사용한다.
         */
        long approvedVideoCount,
        /**
         * 검수 승인 영상 단위 이벤트 분포. 카테고리 구성·순서는 {@link #eventDistribution} 과 동일하며
         * 데이터가 없는 카테고리도 count=0 으로 포함된다.
         */
        List<EventDistributionItem> approvedEventDistribution
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
