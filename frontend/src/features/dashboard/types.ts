// 대시보드 도메인 타입

export type EventTypeCd = 'FIRE' | 'FALL' | 'INVASION' | 'CROWD' | 'VIOLENCE' | 'ABANDON';

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
  // 역할별 KPI
  // WORKER: 4 KPI / REVIEWER: 3 KPI (내 작업 제외)
  totalVideos: number;
  totalLabeledFrames: number;
  reviewPendingCount: number;
  myAssignedCount: number; // WORKER 전용

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
