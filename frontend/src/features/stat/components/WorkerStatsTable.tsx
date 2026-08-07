import { useState } from 'react';

export interface WorkerRow {
  userId: number;
  name: string;
  labeled: number;
  reviewed: number;
  approvalRate: number;
}

export interface WorkerStatsTableProps {
  rows: WorkerRow[];
  loading?: boolean;
}

type SortField = 'labeled' | 'reviewed' | 'autoLabelRate' | 'rejectRate';
type SortDir = 'asc' | 'desc';

/** 작업자 통계 표 — 6컬럼 + 헤더 클릭 정렬 */
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

  const sortSuffix = (f: SortField) =>
    sortField === f ? (sortDir === 'desc' ? ' ↓' : ' ↑') : '';

  const sortedRows = [...rows].sort((a, b) => {
    let aVal: number;
    let bVal: number;
    switch (sortField) {
      case 'labeled':
        aVal = a.labeled;
        bVal = b.labeled;
        break;
      case 'reviewed':
        aVal = a.reviewed;
        bVal = b.reviewed;
        break;
      default:
        aVal = 0;
        bVal = 0;
    }
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
        <thead className="border-b border-gray-100 bg-gray-50 text-table-header text-gray-500">
          <tr>
            <th className="px-4 py-3 text-left font-medium">작업자</th>
            <th className="px-4 py-3 text-right font-medium">
              <button type="button" onClick={() => handleSort('labeled')}>
                완료{sortSuffix('labeled')}
              </button>
            </th>
            <th className="px-4 py-3 text-right font-medium">진행</th>
            <th className="px-4 py-3 text-right font-medium">
              <button type="button" onClick={() => handleSort('reviewed')}>
                라벨{sortSuffix('reviewed')}
              </button>
            </th>
            <th className="px-4 py-3 text-right font-medium">
              <button type="button" onClick={() => handleSort('autoLabelRate')}>
                오토라벨{sortSuffix('autoLabelRate')}
              </button>
            </th>
            <th className="px-4 py-3 text-right font-medium">반려율</th>
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
              const approvalRate =
                typeof r.approvalRate === 'number' && Number.isFinite(r.approvalRate)
                  ? r.approvalRate
                  : null;
              const rejectRate = approvalRate === null ? null : 100 - approvalRate;
              return (
                <tr key={r.userId} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium text-gray-800">{r.name}</td>
                  <td className="px-4 py-3 text-right tabular-nums text-success">
                    {r.labeled.toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-warning">
                    {r.reviewed.toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">
                    {/* "라벨" 헤더는 handleSort('reviewed') 로 정렬한다 — 셀도 같은 필드를
                        렌더해야 헤더 클릭이 실제로 이 컬럼을 재정렬한다(구 버그: r.labeled 를
                        그대로 렌더해 클릭해도 이 컬럼 값이 움직이지 않는 것처럼 보였다). */}
                    {r.reviewed.toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <span className="text-caption text-gray-300">—</span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    {rejectRate === null ? (
                      <span className="text-caption text-gray-300">—</span>
                    ) : (
                      <span
                        className={[
                          'tabular-nums text-label font-medium',
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
