// 통계 도메인 타입 — UI/UX §4-11 통계

import type { EventTypeCd } from '@/features/dashboard/types';

/** 작업자 통계 — KPI 4 + 일별/이벤트별 분포 + 월별 표 */
export interface WorkerStatSummary {
  totalLabeled: number; // 누적 라벨 프레임
  totalReviewed: number; // 누적 검수 건수
  approvalRate: number; // 승인률 (0~100)
  averageElapsedSec: number; // 평균 소요시간 (초)

  /** 일별 완료 — 최근 N일 */
  dailyCompletion: { date: string; count: number }[];
  /** 이벤트 비율 — 6종 */
  eventDistribution: { eventTypeCd: EventTypeCd; label: string; count: number }[];
  /** 월별 표 */
  monthly: { month: string; labeled: number; reviewed: number; approvalRate: number }[];
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
}
