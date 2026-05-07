import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';

import { DataTable, type DataTableColumn } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusBadge } from '@/components/common/StatusBadge';
import { useTasks } from '@/features/task/hooks/useTasks';
import type { Task, TaskListParams } from '@/features/task/types';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * SCR-TASK-001 작업 목록.
 *
 * UI/UX §4-5 정합 — priority/deadline 컬럼은 절대 추가하지 않는다 (회귀 방지).
 *
 * 보안: 사용자 입력은 URL searchParams로만 (XSS 방지 — axios 자동 인코딩).
 */
export function TaskListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const claims = useAuthStore((s) => s.claims);

  const params = useMemo<TaskListParams>(() => {
    const page = Number(searchParams.get('page') ?? '0');
    const size = Number(searchParams.get('size') ?? '20');
    const sort = searchParams.get('sort') ?? undefined;
    // WORKER 본인 자동 필터 (보안): 자신 외 작업 조회 금지.
    // claims.sub은 BE 내부 ID 기반이지만, 화면 단위에서는 워커 본인 화면용으로 남기는 안전 가드.
    const workerIdParam = searchParams.get('workerId');
    const workerId = workerIdParam ? Number(workerIdParam) : undefined;
    return {
      page: Number.isFinite(page) ? page : 0,
      size: Number.isFinite(size) ? size : 20,
      sort,
      workerId,
    };
  }, [searchParams]);

  const { data, isLoading, error } = useTasks(params);

  const updateParams = (next: Partial<TaskListParams>) => {
    const sp = new URLSearchParams(searchParams);
    Object.entries({ ...params, ...next }).forEach(([k, v]) => {
      if (v === undefined || v === null || v === '') sp.delete(k);
      else sp.set(k, String(v));
    });
    setSearchParams(sp, { replace: false });
  };

  const columns: DataTableColumn<Task>[] = [
    { key: 'cctvName', header: 'CCTV명/파일명', render: (t) => t.cctvName },
    { key: 'workerName', header: '담당자', render: (t) => t.workerName },
    {
      key: 'status',
      header: '상태',
      render: (t) => {
        // AssignmentStatus → BadgeStatus 매핑 (안전 매핑)
        const map: Record<string, 'PENDING' | 'IN_PROGRESS' | 'REVIEW_PENDING' | 'COMPLETED' | 'REJECTED'> = {
          PENDING: 'PENDING',
          IN_PROGRESS: 'IN_PROGRESS',
          SUBMITTED: 'REVIEW_PENDING',
          COMPLETED: 'COMPLETED',
          REJECTED: 'REJECTED',
        };
        return <StatusBadge status={map[t.status] ?? 'PENDING'} />;
      },
    },
    {
      key: 'assignedAt',
      header: '배정일시',
      render: (t) => new Date(t.assignedAt).toLocaleString('ko-KR'),
    },
  ];

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="작업 목록"
        description={
          claims?.role === 'REVIEWER'
            ? '배정 현황을 확인하고 재배정할 수 있습니다.'
            : '본인에게 배정된 작업 목록입니다.'
        }
      />
      {error && <ErrorState title="작업 목록을 불러올 수 없습니다" />}
      <DataTable<Task>
        columns={columns}
        rows={data?.content ?? []}
        totalElements={data?.totalElements ?? 0}
        page={params.page ?? 0}
        size={params.size ?? 20}
        sort={params.sort}
        loading={isLoading}
        emptyMessage="배정된 작업이 없습니다"
        rowKey={(t) => t.id}
        onPageChange={(p) => updateParams({ page: p })}
        onSortChange={(s) => updateParams({ sort: s, page: 0 })}
      />
    </section>
  );
}
