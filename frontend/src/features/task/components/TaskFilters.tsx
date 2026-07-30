import { useEffect, useState, type FormEvent } from 'react';
import { RotateCcw, Search } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { useEventTypeLabels } from '@/features/eventType/hooks';
import {
  DEFAULT_TASK_FILTERS,
  MAX_SEARCH_KEYWORD_LENGTH,
  type TaskFilterValues,
} from '@/features/task/boardParams';
import type { Worker } from '@/features/task/types';
import { labelOf } from '@/lib/eventTypeLabel';
import { KRDS_FOCUS } from '@/lib/focusRing';

interface TaskFiltersProps {
  values: TaskFilterValues;
  onChange: (v: TaskFilterValues) => void;
  onReset: () => void;
  /** REVIEWER만 작업자 select 노출 + 서버 workStatus 축 상태 옵션 사용 */
  showAssigneeSelect: boolean;
  workers: Worker[];
  /**
   * 이벤트 유형 **코드** 목록 — 역할별 서버 옵션 API 결과(둘 다 현재 페이지가 아닌 전체 기준).
   * REVIEWER `/v1/tasks/board/event-types` · WORKER `/v1/assignments/event-types`.
   */
  eventTypes: string[];
  /** 서버 옵션이 상한으로 잘렸는지 — true 면 "일부만 표시" 안내를 띄운다. */
  eventTypesTruncated?: boolean;
}

/**
 * 워크플로 상태(BE `workStatus`) 옵션 — ★ BE allowlist 와 **1:1**.
 *
 * `IN_PROGRESS` 는 넣지 않는다: BE `mapBoardStatus` 가 그 값을 절대 반환하지 않아(배정됨=PENDING)
 * 고르면 항상 0건이고, 파라미터로 나가면 400 이다.
 * PENDING 라벨은 행 뱃지('배정 완료')와 KPI 카드('작업중')를 함께 가리키도록 병기한다.
 */
const WORK_STATUS_OPTIONS = [
  { value: '', label: '전체 상태' },
  { value: 'UNASSIGNED', label: '미배정' },
  { value: 'PENDING', label: '배정 완료(작업중)' },
  { value: 'REVIEW_PENDING', label: '검수요청' },
  { value: 'COMPLETED', label: '완료' },
  { value: 'REJECTED', label: '반려' },
] as const;

/**
 * WORKER 시각 상태 옵션 — ★ BE `GET /v1/assignments` 의 `workStatus` allowlist 와 **1:1**.
 *
 * board 축과 값 집합이 다르다: `IN_PROGRESS`(= 그 영상에 라벨 저장 이력이 있음)는 여기서만 유효하고,
 * `미배정`은 본인에게 배정된 행만 다루므로 개념 자체가 없다. 미등록 값을 보내면 BE 가 400 이다.
 */
const WORKER_STATUS_OPTIONS = [
  { value: '', label: '전체 상태' },
  { value: 'PENDING', label: '배정 완료' },
  { value: 'IN_PROGRESS', label: '작업중' },
  { value: 'REVIEW_PENDING', label: '검수요청' },
  { value: 'COMPLETED', label: '완료' },
  { value: 'REJECTED', label: '반려' },
] as const;

/**
 * mock §4-5 정합 — 작업 목록 검색 폼.
 * 영상명/작업자명 input + 이벤트 select + 상태 select + (REVIEWER만) 작업자 select.
 *
 * 보안: 검색어는 부모에서 axios params로만 전달 — XSS/Injection 방지.
 *       입력 길이는 BE `@Size(max=100)` 과 동일하게 제한해 400 왕복을 막는다.
 */
export function TaskFilters({
  values,
  onChange,
  onReset,
  showAssigneeSelect,
  workers,
  eventTypes,
  eventTypesTruncated = false,
}: TaskFiltersProps) {
  const [local, setLocal] = useState<TaskFilterValues>(values);
  // 이벤트 코드 → 한글 카테고리명 (미등록 코드는 원문 폴백).
  const { data: eventLabelMap } = useEventTypeLabels();

  // 외부(KPI 카드 클릭)에서 바뀌는 축은 **상태(workStatus) 하나뿐**이므로 그것만 동기화한다.
  // `values` 전체를 덮으면 아직 제출하지 않은 검색어·이벤트 선택이 카드 클릭 한 번에 사라진다.
  // (초기화는 handleReset 이 local 을 직접 비운다.)
  useEffect(() => {
    setLocal((prev) =>
      prev.workStatus === values.workStatus
        ? prev
        : { ...prev, workStatus: values.workStatus },
    );
  }, [values.workStatus]);

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    onChange(local);
  };

  const handleReset = () => {
    setLocal({ ...DEFAULT_TASK_FILTERS });
    onReset();
  };

  const statusOptions = showAssigneeSelect
    ? WORK_STATUS_OPTIONS
    : WORKER_STATUS_OPTIONS;

  return (
    <form
      onSubmit={handleSubmit}
      data-testid="task-filters"
      className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white px-4 py-3 shadow-sm"
    >
      {/* 검색 */}
      <div className="flex min-w-[180px] flex-1 flex-col gap-1">
        <label
          htmlFor="task-filter-q"
          className="text-xs font-medium text-gray-500"
        >
          영상명 / 작업자명
        </label>
        <div className="relative">
          <Search
            size={14}
            className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400"
            aria-hidden
          />
          <input
            id="task-filter-q"
            type="text"
            value={local.q}
            maxLength={MAX_SEARCH_KEYWORD_LENGTH}
            onChange={(e) => setLocal((p) => ({ ...p, q: e.target.value }))}
            placeholder="검색어 입력"
            className={`w-full rounded-md border border-gray-300 py-1.5 pl-8 pr-3 text-sm ${KRDS_FOCUS}`}
          />
        </div>
      </div>

      {/* 이벤트 유형 */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="task-filter-event"
          className="text-xs font-medium text-gray-500"
        >
          이벤트
        </label>
        <select
          id="task-filter-event"
          value={local.eventTypeCd}
          onChange={(e) =>
            setLocal((p) => ({ ...p, eventTypeCd: e.target.value }))
          }
          className={`rounded-md border border-gray-300 px-2 py-1.5 text-sm ${KRDS_FOCUS}`}
        >
          <option value="">전체</option>
          {eventTypes.map((code) => (
            <option key={code} value={code}>
              {labelOf(eventLabelMap, code)}
            </option>
          ))}
        </select>
        {eventTypesTruncated && (
          <p
            data-testid="event-type-truncated"
            className="text-xs text-gray-500"
          >
            옵션이 많아 일부만 표시됩니다
          </p>
        )}
      </div>

      {/* 상태 */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="task-filter-status"
          className="text-xs font-medium text-gray-500"
        >
          상태
        </label>
        <select
          id="task-filter-status"
          value={local.workStatus}
          onChange={(e) =>
            setLocal((p) => ({ ...p, workStatus: e.target.value }))
          }
          className={`rounded-md border border-gray-300 px-2 py-1.5 text-sm ${KRDS_FOCUS}`}
        >
          {statusOptions.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </div>

      {/* 작업자 (REVIEWER 전용) */}
      {showAssigneeSelect && (
        <div className="flex flex-col gap-1">
          <label
            htmlFor="task-filter-assignee"
            className="text-xs font-medium text-gray-500"
          >
            작업자
          </label>
          <select
            id="task-filter-assignee"
            value={local.assigneeId}
            onChange={(e) =>
              setLocal((p) => ({ ...p, assigneeId: e.target.value }))
            }
            className={`rounded-md border border-gray-300 px-2 py-1.5 text-sm ${KRDS_FOCUS}`}
          >
            <option key="__all__" value="">전체 작업자</option>
            {workers.map((w, idx) => (
              <option
                key={w.id ?? `worker-${idx}`}
                value={String(w.id ?? '')}
              >
                {w.name ?? '(이름 없음)'}
              </option>
            ))}
          </select>
        </div>
      )}

      <div className="flex items-end gap-2">
        <Button type="submit" variant="primary" size="sm">
          <Search size={14} aria-hidden />
          조회
        </Button>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={handleReset}
        >
          <RotateCcw size={14} aria-hidden />
          초기화
        </Button>
      </div>
    </form>
  );
}

export default TaskFilters;
