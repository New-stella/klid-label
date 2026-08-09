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
