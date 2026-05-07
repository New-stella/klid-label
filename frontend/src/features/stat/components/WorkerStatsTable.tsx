import { DataTable, type DataTableColumn } from '@/components/common/DataTable';

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

/** 작업자 통계 표 — 페이징 없는 단순 표 (통상 10~20명 규모) */
export function WorkerStatsTable({ rows, loading }: WorkerStatsTableProps) {
  const columns: DataTableColumn<WorkerRow>[] = [
    { key: 'name', header: '이름', render: (r) => r.name },
    {
      key: 'labeled',
      header: '라벨 프레임',
      align: 'right',
      render: (r) => r.labeled.toLocaleString('ko-KR'),
    },
    {
      key: 'reviewed',
      header: '검수 건수',
      align: 'right',
      render: (r) => r.reviewed.toLocaleString('ko-KR'),
    },
    {
      key: 'approvalRate',
      header: '승인률',
      align: 'right',
      render: (r) => `${r.approvalRate.toFixed(1)}%`,
    },
  ];

  return (
    <div data-testid="worker-stats-table">
      <DataTable<WorkerRow>
        columns={columns}
        rows={rows}
        totalElements={rows.length}
        page={0}
        size={rows.length || 1}
        loading={loading}
        emptyMessage="작업자 통계가 없습니다"
        rowKey={(r) => r.userId}
        onPageChange={() => {
          // 페이징 미사용
        }}
      />
    </div>
  );
}
