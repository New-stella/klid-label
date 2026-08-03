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
        List<EventDistributionItem> approvedEventDistribution,
        /**
         * 최근 30일(오늘 포함) 일별 검수 완료 건수 — "일별 작업량" 막대차트용.
         *
         * <p><b>항상 정확히 30건</b>이며 작업이 없던 날도 {@code count=0} 으로 채워진다(0-fill).
         * 막대차트 X축이 날짜 연속으로 그려져야 하기 때문이다. {@code date} 는 {@code yyyy-MM-dd}
         * 오름차순이고 마지막 항목이 오늘이다.
         *
         * <p>집계 기준은 <b>전체(모든 작업자)</b>이며 {@link #approvedVideoCount} 와 동일하게
         * {@code LS_RAW_DATA_STATUS} 의 APPROVED 행을 원천으로 한다(파생영상 포함 여부도 동일).
         *
         * <p>항목 타입은 작업자 통계({@link WorkerStatSummaryResponse.DailyCompletion})와 같은
         * {@code (date, count)} 값 레코드를 <b>재사용</b>한다 — 두 화면이 같은 JSON 키를 쓰므로
         * 동일 구조 레코드를 따로 선언하면 한쪽만 이름이 바뀌는 드리프트가 생긴다.
         * 다만 <b>작업자 경로는 sparse(데이터 있는 날만)</b> 로 유지되며 0-fill 은 본 필드 전용이다.
         */
        List<WorkerStatSummaryResponse.DailyCompletion> dailyCounts
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
