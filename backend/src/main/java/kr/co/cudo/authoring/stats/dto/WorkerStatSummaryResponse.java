package kr.co.cudo.authoring.stats.dto;

import java.util.List;

/**
 * SCR-STAT-001 작업자 통계 응답.
 *
 * <p>FE {@code WorkerStatSummary} (frontend/src/features/stat/types.ts) 와 1:1 매칭.
 * 모든 카운트는 0 이상, 비율은 0.0~1.0 범위. 데이터 없는 작업자는 모든 필드가 0 또는 빈 배열로 응답한다.
 *
 * @param workerId          대상 작업자 USER_NO (문자열) — FE 비교 호환.
 * @param workerName        대상 작업자 이름. 미존재 시 null.
 * @param completed         LABELER 배정 중 APPROVED 상태 영상 수.
 * @param inProgress        LABELER 배정 중 아직 완료되지 않은(APPROVED 가 아닌) 영상 수.
 *                          판정 축은 전체 구축 현황 화면과 동일한
 *                          {@code StatsQueryRepository.IN_PROGRESS_PREDICATE} 하나다 —
 *                          진행 상태를 열거하지 않는 이유는 그 상수 주석 참조.
 * @param rejected          REJECTED 상태 영상 수.
 * @param labelCount        배정된 raw 의 LsDataSrc 에 달린 LsDataLbl 총 수.
 * @param autoLabelRate     위 라벨 집합에서 자동 생성 라벨 비율(0.0~1.0). 분모 0 → 0.0.
 *                          판정 축은 전체 구축 현황 화면과 동일한
 *                          {@code StatsQueryRepository.AUTO_LABEL_PREDICATE} 하나다.
 *                          <b>단위는 비율(0~1)</b>이며 전체 구축 현황의 같은 이름 지표는
 *                          백분율(0~100)이다 — 이 비대칭은 외부 FE 계약이라 유지한다.
 * @param rejectRate        REJECTED / (APPROVED + REJECTED). 분모 0 → 0.0.
 * @param dailyCompletion   최근 30일 일별 완료(APPROVED) 카운트.
 * @param monthly           최근 12개월 월별 완료/반려/라벨 카운트.
 */
public record WorkerStatSummaryResponse(
        String workerId,
        String workerName,
        long completed,
        long inProgress,
        long rejected,
        long labelCount,
        double autoLabelRate,
        double rejectRate,
        List<DailyCompletion> dailyCompletion,
        List<MonthlyRow> monthly
) {

    public record DailyCompletion(String date, long count) {
    }

    public record MonthlyRow(String month, long completed, long rejected, long labelCount) {
    }

    /** 데이터 없는 작업자에 대한 안전 응답. */
    public static WorkerStatSummaryResponse empty(String workerId, String workerName) {
        return new WorkerStatSummaryResponse(
                workerId,
                workerName,
                0L,
                0L,
                0L,
                0L,
                0.0,
                0.0,
                List.of(),
                List.of()
        );
    }
}
