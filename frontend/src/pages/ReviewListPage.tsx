import { useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { DataTable, type DataTableColumn } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusBadge } from '@/components/common/StatusBadge';
import { useReviewList } from '@/features/review/hooks/useReviewList';
import type { Review, ReviewListParams } from '@/features/review/types';

/**
 * SCR-REVIEW-001 검수 대기 목록.
 *
 * UI/UX §4-9 정합 — DataTable 컬럼: 작업자/제출일/라벨 수 + [검수 시작 ▶].
 *
 * 보안:
 * - URL searchParams로 페이지 파라미터만 전달 (axios 자동 URL 인코딩 — XSS 방어).
 * - REVIEWER 역할 검증은 라우터 RoleGuard에서 (이중 검증 — BE도 동일).
 */
export function ReviewListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();

  const params = useMemo<ReviewListParams>(() => {
    const page = Number(searchParams.get('page') ?? '0');
    const size = Number(searchParams.get('size') ?? '20');
    const sort = searchParams.get('sort') ?? undefined;
    return {
      page: Number.isFinite(page) ? page : 0,
      size: Number.isFinite(size) ? size : 20,
      sort,
    };
  }, [searchParams]);

  const { data, isLoading, error } = useReviewList(params);

  const updateParams = (next: Partial<ReviewListParams>) => {
    const sp = new URLSearchParams(searchParams);
    Object.entries({ ...params, ...next }).forEach(([k, v]) => {
      if (v === undefined || v === null || v === '') sp.delete(k);
      else sp.set(k, String(v));
    });
    setSearchParams(sp, { replace: false });
  };

  const columns: DataTableColumn<Review>[] = [
    { key: 'cctvName', header: 'CCTV명/파일명', render: (r) => r.cctvName },
    { key: 'workerName', header: '작업자', render: (r) => r.workerName },
    {
      key: 'submittedAt',
      header: '제출일',
      sortable: true,
      render: (r) => new Date(r.submittedAt).toLocaleString('ko-KR'),
    },
    {
      key: 'labelCount',
      header: '라벨 수',
      align: 'right',
      render: (r) => r.labelCount.toLocaleString('ko-KR'),
    },
    {
      key: 'status',
      header: '상태',
      render: (r) => <StatusBadge status={r.status} />,
    },
    {
      key: 'actions',
      header: '액션',
      render: (r) => (
        <Button
          variant="primary"
          size="sm"
          onClick={() => navigate(`/review/${r.id}`)}
          aria-label={`검수 시작 ${r.cctvName}`}
        >
          검수 시작 ▶
        </Button>
      ),
    },
  ];

  return (
    <section className="flex flex-col gap-4" data-testid="review-list-page">
      <PageHeader
        title="검수 대기 목록"
        description="작업자가 제출한 라벨링 결과를 검수합니다."
      />
      {error && <ErrorState title="검수 목록을 불러올 수 없습니다" />}
      <DataTable<Review>
        columns={columns}
        rows={data?.content ?? []}
        totalElements={data?.totalElements ?? 0}
        page={params.page ?? 0}
        size={params.size ?? 20}
        sort={params.sort}
        loading={isLoading}
        emptyMessage="검수 대기 항목이 없습니다"
        rowKey={(r) => r.id}
        onPageChange={(p) => updateParams({ page: p })}
        onSortChange={(s) => updateParams({ sort: s, page: 0 })}
      />
    </section>
  );
}
