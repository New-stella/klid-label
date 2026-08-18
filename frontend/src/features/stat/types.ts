// 통계 도메인 타입 — UI/UX §4-11 통계

/** 작업자 통계 — KPI + 일별/월별 — mock WorkerStat 정합 */
export interface WorkerStatSummary {
  workerId: string;
  workerName: string;
  completed: number; // 완료 작업
  inProgress: number; // 진행 중
  rejected: number; // 반려
  labelCount: number; // 총 라벨 수
  autoLabelRate: number; // 오토라벨 비율 (0~1)
  rejectRate: number; // 반려율 (0~1)

  /** 일별 완료 — 최근 30일 */
  dailyCompletion: { date: string; count: number }[];
  /** 월별 통계 — 최근 12개월 */
  monthly: { month: string; completed: number; rejected: number; labelCount: number }[];

  /**
   * 배정된 <b>전체</b> 영상 수 = completed + inProgress (BE `WorkerStatSummaryResponse`).
   * rejected 는 이미 inProgress(=APPROVED 아님) 안에 들어 있어 따로 더하지 않는다.
   * 합을 화면에서 재유도하지 않는다 — 어느 한쪽 정의가 바뀌면 조용히 어긋난다.
   *
   * optional 로 두지 않는다 — `?? 0` 폴백이 미수신을 실데이터 0 으로 오인시키기 때문.
   */
  assignedTotal: number;

  /**
   * 완료율 — <b>비율(0.0~1.0)</b>이며 백분율이 아니다. 분모가 0 이면 0.0.
   *
   * ⚠ 전체 구축 현황(OverallStatSummary)의 approvalRate·autoLabelRate 는 <b>백분율(0~100)</b>
   * 이라 단위가 다르다 — 같은 함수로 처리하지 말 것(BE 계약의 기존 비대칭).
   * 표시할 때만 백분율로 바꾸고, 화면에서 completed/assignedTotal 을 다시 나누지 않는다.
   */
  completionRate: number;

  /**
   * 검수완료(APPROVED) 영상의 라벨 수 — <b>학습데이터로 확정된 분량</b>. 카드의 "주 수치".
   * 폐기된 프레임(R4)의 라벨은 제외한다(산출물·데이터마트와 같은 기준).
   * 전체 배정분을 세는 {@link labelCount} 와 짝이며 항상 approvedLabelCount ≤ labelCount.
   */
  approvedLabelCount: number;
}

export type StatPeriod = 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR';

/** 전체 구축 현황 — REVIEWER 전용 */
export interface OverallStatSummary {
  /** 누적 카드 2종 (전체 기준 — 미검수 포함). ProgressBar 표시 절대 금지 (UI/UX §4-11) */
  cumulativeImageCount: number;
  cumulativeVideoCount: number;

  /**
   * 검수완료(APPROVED) 기준 누적 — 카드의 "주 수치".
   * 검수 승인 = 작업 완료 = 학습데이터 확정 정책. 위 전체 기준 값은 보조로만 병기한다.
   * optional 로 두지 않는다 — `?? 0` 폴백이 미수신을 실데이터 0 으로 오인시키기 때문.
   */
  approvedImageCount: number;
  approvedVideoCount: number;

  /** 처리 현황 5 카드 */
  processing: {
    pending: number;
    inProgress: number;
    reviewPending: number;
    approved: number;
    rejected: number;
  };

  /** 이벤트 분포(전체 기준) — BE 카테고리 항목(eventTypeCd=categoryKey, label, count)을 그대로 순회 */
  eventDistribution: { eventTypeCd: string; label: string; count: number }[];

  /** 이벤트 분포(검수완료 기준) — 카드와 같은 기준으로 화면에 렌더하는 값 */
  approvedEventDistribution: { eventTypeCd: string; label: string; count: number }[];

  /**
   * 작업자별 통계.
   * 두 비율(approvalRate·autoLabelRate)은 모두 <b>백분율(0~100)</b>이며 분모가 0 이면 0 이다.
   */
  workers: {
    userId: number;
    name: string;
    /** 라벨링한 영상 수 */
    labeled: number;
    /** 검수한 영상 수 */
    reviewed: number;
    /** 승인율 — 백분율(0~100) */
    approvalRate: number;
    /** 배정됐고 아직 완료되지 않은 작업 수 */
    inProgress: number;
    /** 자동 생성 라벨 비율 — 백분율(0~100) */
    autoLabelRate: number;
  }[];

  /** 일별 전체 작업량 (최근 30일) — OverallStatPage 차트용 */
  dailyCounts?: { date: string; count: number }[];
}
