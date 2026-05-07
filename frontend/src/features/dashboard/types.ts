// 대시보드 도메인 타입

export type EventTypeCd =
  | 'FALL'
  | 'VIOLENCE'
  | 'TRAFFIC_ACCIDENT'
  | 'ABNORMAL_BEHAVIOR'
  | 'FLOOD'
  | 'WILDFIRE';

export interface EventDistribution {
  eventTypeCd: EventTypeCd;
  label: string;
  count: number;
}

export interface MyTask {
  pendingCount: number;
  inProgressCount: number;
  reviewPendingCount: number;
  rejectedCount: number;
}

export interface Notice {
  id: number;
  title: string;
  pinned: boolean;
  createdAt: string;
}

export interface DashboardSummary {
  // 역할별 KPI (UI/UX §4-3)
  // WORKER: 4 KPI (처리 대기 / 처리 완료 / 내 작업 / 반려 건수)
  // REVIEWER: 3 KPI (처리 대기 / 처리 완료 / 반려 건수 — 내 작업 제외)
  pendingCount: number; // 처리 대기
  completedCount: number; // 처리 완료
  myTaskCount: number; // 내 작업 (WORKER 전용)
  rejectedCount: number; // 반려 건수

  // 누적 카드 2종
  cumulativeImageCount: number; // 목표 10만장
  cumulativeVideoCount: number; // 목표 5,000건

  // 6종 이벤트 분포
  eventDistribution: EventDistribution[];

  // 내 작업 현황 (WORKER 전용)
  myTask: MyTask;

  // 공지사항
  notices: Notice[];
}
