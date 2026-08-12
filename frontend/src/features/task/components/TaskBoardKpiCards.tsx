import { KpiCard } from '@/components/common/KpiCard';
import { Skeleton } from '@/components/common/Skeleton';

import { WORK_STATUS_PARAMS, type TaskBoardSummary, type WorkStatusParam } from '../types';

interface TaskBoardKpiCardsProps {
  summary: TaskBoardSummary | undefined;
  isLoading: boolean;
  isError: boolean;
  /** 현재 선택된 워크플로 필터 (빈 문자열 = 전체). */
  selected: string;
  /** 카드 클릭 — 같은 카드를 다시 누르면 `undefined` 로 해제 신호를 준다. */
  onSelect: (workStatus: WorkStatusParam | undefined) => void;
}

/**
 * 작업목록 KPI 5카드 (REVIEWER 전용).
 *
 * 숫자는 **서버 집계(GET /v1/tasks/board/summary) = 필터 결과 전체 기준**이다 —
 * 현재 페이지 20건 안에서 세지 않는다.
 *
 * 카드 ↔ 파라미터 매핑 (★ 모두 `workStatus` 축이다 — `status` 축이 아니다):
 *   전체 = (필터 없음) / 미배정 = UNASSIGNED / 작업중 = PENDING /
 *   검수요청 = REVIEW_PENDING / 반려 = REJECTED
 *
 * 상태 3종(로딩 / 정상 / 실패)을 구분 렌더한다 — 로딩을 0건으로 오인하면 "작업이 없다"는 오판이 된다.
 */
export function TaskBoardKpiCards({
  summary,
  isLoading,
  isError,
  selected,
  onSelect,
}: TaskBoardKpiCardsProps) {
  if (isLoading) {
    return (
      <div data-testid="kpi-loading" className="grid grid-cols-5 gap-4">
        {Array.from({ length: 5 }).map((_, i) => (
          <div
            key={i}
            className="rounded-lg border border-gray-200 bg-white px-6 py-5 shadow-sm"
          >
            <Skeleton height={14} />
            <div className="mt-2">
              <Skeleton height={24} width="50%" />
            </div>
          </div>
        ))}
      </div>
    );
  }

  if (isError || !summary) {
    // 목록은 정상일 수 있으므로 페이지 전체를 막지 않고 카드 영역에만 표시한다.
    return (
      <div
        data-testid="kpi-error"
        role="status"
        className="rounded-lg border border-gray-200 bg-gray-50 px-4 py-3 text-body-md text-gray-600"
      >
        집계 정보를 불러오지 못했습니다. 목록은 정상 표시됩니다.
      </div>
    );
  }

  // 같은 카드를 다시 누르면 해제(전체) — 토글.
  const toggle = (value: WorkStatusParam) =>
    onSelect(selected === value ? undefined : value);

  return (
    <div className="grid grid-cols-5 gap-4">
      <KpiCard
        data-testid="kpi-total"
        label="전체 작업"
        value={summary.total}
        selected={selected === ''}
        onClick={() => onSelect(undefined)}
      />
      <KpiCard
        data-testid="kpi-unassigned"
        label="미배정"
        value={summary.unassigned}
        selected={selected === WORK_STATUS_PARAMS.UNASSIGNED}
        onClick={() => toggle(WORK_STATUS_PARAMS.UNASSIGNED)}
      />
      <KpiCard
        data-testid="kpi-inProgress"
        label="작업중"
        value={summary.inProgress}
        selected={selected === WORK_STATUS_PARAMS.PENDING}
        onClick={() => toggle(WORK_STATUS_PARAMS.PENDING)}
      />
      <KpiCard
        data-testid="kpi-reviewPending"
        label="검수요청"
        value={summary.reviewPending}
        selected={selected === WORK_STATUS_PARAMS.REVIEW_PENDING}
        onClick={() => toggle(WORK_STATUS_PARAMS.REVIEW_PENDING)}
      />
      <KpiCard
        data-testid="kpi-rejected"
        label="반려"
        value={summary.rejected}
        selected={selected === WORK_STATUS_PARAMS.REJECTED}
        onClick={() => toggle(WORK_STATUS_PARAMS.REJECTED)}
      />
    </div>
  );
}

export default TaskBoardKpiCards;
