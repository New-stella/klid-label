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
        List<NoticeItem> notices,
        /**
         * 검수 승인(APPROVED) 영상에 속한 프레임(LS_DATA_SRC) 누적 건수 — "이미지 학습데이터" 카드용.
         *
         * <p>{@link #cumulativeImageCount} 는 검수 여부와 무관한 전건이라 미검수 영상까지 포함한다.
         * 핵심 산출물 목표(이미지 10만장) 진척은 <b>검수 승인 = 작업 완료 = 학습데이터 확정</b> 정책상
         * 이 필드로 판단한다. 항상 {@code approvedImageCount <= cumulativeImageCount}.
         */
        long approvedImageCount,
        /**
         * 검수 승인(APPROVED) 영상 누적 건수 — "영상 학습데이터" 카드용.
         * {@link #completedCount} 와 동일 원천(LS_RAW_DATA_STATUS APPROVED 카운트)을 사용한다.
         */
        long approvedVideoCount,
        /**
         * 검수 승인 영상 단위 이벤트 분포. 카테고리 구성·순서는 {@link #eventDistribution} 과 동일하며
         * 데이터가 없는 카테고리도 count=0 으로 포함된다(항목 수·순서 고정 — FE 가 같은 그리드에 렌더).
         */
        List<EventDistributionItem> approvedEventDistribution,
        /**
         * 검수 승인 영상의 프레임 단위 이벤트 분포. 카테고리 구성·순서는 {@link #imageDistribution} 과 동일.
         */
        List<EventDistributionItem> approvedImageDistribution
) {
}
