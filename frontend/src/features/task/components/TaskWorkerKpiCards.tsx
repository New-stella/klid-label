import { Flame, ListTodo, Play, XCircle } from 'lucide-react';

import { KpiCard } from '@/components/common/KpiCard';

import type { RowStatus } from '../statusLabels';

interface TaskWorkerKpiCardsProps {
  /** 현재 화면에 표시 중인 행의 상태 목록 (WORKER 는 본인 배정분만 조회한다). */
  rowStatuses: RowStatus[];
}

/**
 * WORKER 시각 KPI 4카드 — **클라이언트 집계**.
 *
 * WORKER 는 REVIEWER 전용 집계 API(`/v1/tasks/board/summary`)를 호출할 수 없으므로(403)
 * 기존과 동일하게 본인 배정 목록(`/v1/assignments`) 결과를 세어 표시한다.
 * 필터/카드 클릭 연동은 없다(표시 전용) — 구 동작 그대로다.
 */
export function TaskWorkerKpiCards({ rowStatuses }: TaskWorkerKpiCardsProps) {
  const count = (status: RowStatus) =>
    rowStatuses.filter((s) => s === status).length;

  return (
    <div className="grid grid-cols-4 gap-4">
      <KpiCard
        data-testid="kpi-total"
        label="전체 작업"
        value={rowStatuses.length}
        icon={<ListTodo size={22} className="text-primary-600" aria-hidden />}
        iconBgClassName="bg-primary-50"
      />
      <KpiCard
        data-testid="kpi-inProgress"
        label="작업중"
        value={count('IN_PROGRESS')}
        icon={<Play size={22} className="text-success" aria-hidden />}
        iconBgClassName="bg-success/10"
      />
      <KpiCard
        data-testid="kpi-reviewPending"
        label="검수요청"
        value={count('REVIEW_PENDING')}
        icon={<Flame size={22} className="text-warning" aria-hidden />}
        iconBgClassName="bg-warning/10"
      />
      <KpiCard
        data-testid="kpi-rejected"
        label="반려"
        value={count('REJECTED')}
        icon={<XCircle size={22} className="text-danger" aria-hidden />}
        iconBgClassName="bg-danger/10"
      />
    </div>
  );
}

export default TaskWorkerKpiCards;
