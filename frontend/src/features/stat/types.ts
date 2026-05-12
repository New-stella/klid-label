// 통계 도메인 타입 — UI/UX §4-11 통계

import type { EventTypeCd } from '@/features/dashboard/types';

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
  /** 누적 카드 2종 — 진행률 표시 절대 금지 (UI/UX §4-11) */
  cumulativeImageCount: number;
  cumulativeVideoCount: number;

  /** 처리 현황 5 카드 */
  processing: {
    pending: number;
    inProgress: number;
    reviewPending: number;
    approved: number;
    rejected: number;
  };

  /** 이벤트 분포 6종 고정 */
  eventDistribution: { eventTypeCd: EventTypeCd; label: string; count: number }[];

  /** 작업자별 통계 */
  workers: {
    userId: number;
    name: string;
    labeled: number;
    reviewed: number;
    approvalRate: number;
  }[];

  /** 일별 전체 작업량 (최근 30일) — OverallStatPage 차트용 */
  dailyCounts?: { date: string; count: number }[];
}
