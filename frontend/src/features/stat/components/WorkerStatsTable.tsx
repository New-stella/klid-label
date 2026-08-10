import { useState } from 'react';

import { cn } from '@/lib/cn';

export interface WorkerRow {
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
  /** 자동 생성 라벨 비율 — 백분율(0~100). approvalRate 와 같은 축 */
  autoLabelRate: number;
}

export interface WorkerStatsTableProps {
  rows: WorkerRow[];
  loading?: boolean;
}

type SortField = 'labeled' | 'inProgress' | 'reviewed' | 'autoLabelRate';
type SortDir = 'asc' | 'desc';

/**
 * 컬럼 정의 — <b>헤더 라벨·정렬 키·렌더할 값이 한 곳에서 함께 정해진다</b>.
 *
 * 이 표의 기존 결함은 헤더가 A 로 정렬하면서 셀은 B 를 그리고, 두 컬럼이 같은 필드를 중복으로
 * 그리던 것이었다. 헤더와 셀이 서로 다른 자리에서 필드를 고르는 한 그 어긋남은 다시 생긴다.
 * 그래서 정렬 키({@link SortField})와 표시 값을 <b>같은 항목</b>에서 꺼낸다.
 */
const NUMERIC_COLUMNS: { field: SortField; label: string; pick: (r: WorkerRow) => number }[] = [
  { field: 'labeled', label: '라벨', pick: (r) => r.labeled },
  { field: 'inProgress', label: '진행', pick: (r) => r.inProgress },
  { field: 'reviewed', label: '검수', pick: (r) => r.reviewed },
  { field: 'autoLabelRate', label: '오토라벨', pick: (r) => r.autoLabelRate },
];

/**
 * 표 헤더 셀 클래스 — 모든 `<th>` 가 이 한 값을 공유한다(정렬만 호출부에서 덧붙인다).
 *
 * ⚠ **반드시 `<th>` 에 직접 건다.** 구 구현은 글자 클래스를 `<thead>` 에 걸고 `<th>` 에는
 * `font-medium`(500)을 따로 뒀다 — 그 결과 `text-table-header` 의 600 이 `<th>` 에 도달하지
 * 못하고 500 으로 렌더됐다. `<tr>`/`<thead>` 에만 걸어도 마찬가지인데, 그때는 브라우저 UA
 * 기본 `th { font-weight: bold }`(700)가 상속값을 이긴다.
 * ⚠ 굵기는 `text-table-header` step(600)이 단독으로 정한다 — 별도 굵기 클래스를 겹치지 않는다.
 *
 * 글자색 하한은 `gray-600` 이다 — 헤더 배경이 secondary-50(#EEF2F7)이라 gray-500 은
 * 그 위에서 4.01:1 로 AA(4.5:1) 미달이다(gray-600 은 5.60:1).
 */
const TH_CLASS = 'px-4 py-3 text-left text-table-header uppercase tracking-wide text-gray-600';

/** 응답에 값이 없거나 숫자가 아니면 null — 그때만 자리표시('—')를 그린다. */
function finiteOrNull(value: number | undefined | null): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function Placeholder() {
  return <span className="text-caption text-gray-300">—</span>;
}

/** 작업자 통계 표 — 작업자/라벨/진행/검수/오토라벨/반려율 6컬럼 + 헤더 클릭 정렬 */
export function WorkerStatsTable({ rows, loading }: WorkerStatsTableProps) {
  const [sortField, setSortField] = useState<SortField>('labeled');
  const [sortDir, setSortDir] = useState<SortDir>('desc');

  const handleSort = (field: SortField) => {
    if (sortField === field) {
      setSortDir((d) => (d === 'desc' ? 'asc' : 'desc'));
    } else {
      setSortField(field);
      setSortDir('desc');
    }
  };

  const sortSuffix = (f: SortField) => (sortField === f ? (sortDir === 'desc' ? ' ↓' : ' ↑') : '');

  const pickSortValue = NUMERIC_COLUMNS.find((c) => c.field === sortField)?.pick;
  const sortedRows = [...rows].sort((a, b) => {
    const aVal = finiteOrNull(pickSortValue?.(a)) ?? 0;
    const bVal = finiteOrNull(pickSortValue?.(b)) ?? 0;
    return sortDir === 'desc' ? bVal - aVal : aVal - bVal;
  });

  if (loading) {
    return (
      <div data-testid="worker-stats-table" className="p-4 text-body-md text-gray-400">
        불러오는 중…
      </div>
    );
  }

  return (
    <div data-testid="worker-stats-table">
      <table className="w-full text-body-md">
        {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과 같은
            회색을 쓰면 열 구조가 먼저 읽히지 않는다. 글자색 gray-600 은 그 위에서
            5.60:1 로 AA 를 만족한다(gray-500 은 4.01 로 미달).
            `<thead>` 에는 배경·테두리만 두고 **글자 축은 `<th>`(TH_CLASS)** 가 갖는다. */}
        <thead className="border-b border-gray-100 bg-secondary-50">
          <tr>
            <th className={TH_CLASS}>작업자</th>
            {NUMERIC_COLUMNS.map((col) => (
              <th
                key={col.field}
                className={cn(TH_CLASS, 'text-right')}
                /* 정렬 상태를 화살표(시각)뿐 아니라 보조기술에도 알린다. 버튼에 aria-label 을
                   따로 붙이지 않는 이유는 접근성 이름이 보이는 텍스트와 갈라지기 때문이다. */
                aria-sort={
                  sortField === col.field
                    ? sortDir === 'desc'
                      ? 'descending'
                      : 'ascending'
                    : 'none'
                }
              >
                <button type="button" onClick={() => handleSort(col.field)}>
                  {col.label}
                  {sortSuffix(col.field)}
                </button>
              </th>
            ))}
            <th className={cn(TH_CLASS, 'text-right')}>반려율</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-50">
          {sortedRows.length === 0 ? (
            <tr>
              <td colSpan={6} className="px-4 py-6 text-center text-gray-400">
                작업자 통계가 없습니다
              </td>
            </tr>
          ) : (
            sortedRows.map((r) => {
              const labeled = finiteOrNull(r.labeled);
              const inProgress = finiteOrNull(r.inProgress);
              const reviewed = finiteOrNull(r.reviewed);
              const autoLabelRate = finiteOrNull(r.autoLabelRate);
              const approvalRate = finiteOrNull(r.approvalRate);
              const rejectRate = approvalRate === null ? null : 100 - approvalRate;
              return (
                <tr key={r.userId} className="hover:bg-rowHover">
                  <td className="px-4 py-3 font-medium text-gray-800">{r.name}</td>
                  <td className="px-4 py-3 text-right tabular-nums text-success">
                    {labeled === null ? <Placeholder /> : labeled.toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-warning">
                    {inProgress === null ? <Placeholder /> : inProgress.toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">
                    {reviewed === null ? <Placeholder /> : reviewed.toLocaleString()}
                  </td>
                  {/* ⚠ 오토라벨률·반려율은 **읽는 수치**라 같은 행의 라벨/진행/검수와 같은
                      본문 크기(17px)를 쓴다. 구 구현은 이 둘만 `text-label`(14px)이라
                      한 행 안에서 크기가 갈렸다 — 배지가 아니므로 축소 대상이 아니다. */}
                  <td className="px-4 py-3 text-right">
                    {autoLabelRate === null ? (
                      <Placeholder />
                    ) : (
                      <span className="tabular-nums text-body-md font-medium text-gray-600">
                        {autoLabelRate.toFixed(1)}%
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-right">
                    {rejectRate === null ? (
                      <Placeholder />
                    ) : (
                      <span
                        className={[
                          'tabular-nums text-body-md font-medium',
                          rejectRate > 10 ? 'text-danger' : 'text-gray-600',
                        ].join(' ')}
                      >
                        {rejectRate.toFixed(1)}%
                      </span>
                    )}
                  </td>
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </div>
  );
}
