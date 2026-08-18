package kr.co.cudo.authoring.stats.dto;

import java.util.List;

/**
 * SCR-STAT-001 작업자 통계 응답.
 *
 * <p>@design API-056, SCREEN-020
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
 * @param assignedTotal     그 작업자에게 배정된 <b>전체</b> 영상 수 = {@code completed + inProgress}.
 *                          <b>⚠ {@link #rejected} 는 이미 {@link #inProgress}(= APPROVED 아님) 안에
 *                          들어 있어 따로 더하지 않는다</b> — {@code completed + inProgress + rejected}
 *                          로 읽으면 반려 건을 두 번 센다. 두 값은 상보 집합(APPROVED / APPROVED 아님)이라
 *                          합이 곧 배정 총계다. 값을 <b>서버가 직접 내려주는</b> 이유는 화면이 합을
 *                          재유도하면 어느 한쪽 정의가 바뀔 때 조용히 어긋나기 때문이다.
 * @param completionRate    {@code completed / assignedTotal}. <b>단위는 비율(0.0~1.0)</b>이며
 *                          분모가 0 이면 0.0. 전체 구축 현황(SCR-STAT-002)의 같은 성격 지표
 *                          ({@code approvalRate}·{@code autoLabelRate})는 <b>백분율(0~100)</b>이다 —
 *                          이 비대칭은 {@link #autoLabelRate} 와 동일한 기존 계약이라 통일하지 않는다.
 * @param approvedLabelCount 검수완료(APPROVED) 영상에 달린 라벨 수 — <b>학습데이터로 확정된 분량</b>이다.
 *                          전체 배정분을 세는 {@link #labelCount} 와 짝이며 화면은 이 값을 주 수치로 쓴다.
 *                          항상 {@code approvedLabelCount <= labelCount}.
 *                          <b>폐기된 프레임(R4)의 라벨은 제외</b>한다 — 산출물·데이터마트가 폐기 프레임을
 *                          구조적으로 빼므로 같은 기준을 쓴다. 기준은 <b>최신 버전 하나</b>이며 재승인으로
 *                          쌓인 이전 버전 스냅샷은 세지 않는다(라이브 작업본을 센다).
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
        List<MonthlyRow> monthly,
        long assignedTotal,
        double completionRate,
        long approvedLabelCount
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
                List.of(),
                0L,
                0.0,
                0L
        );
    }
}
