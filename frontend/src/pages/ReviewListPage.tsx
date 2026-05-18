import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ClipboardCheck, Hourglass, CheckCircle2, XCircle } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { DataTable, type DataTableColumn } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { KpiCard } from '@/components/common/KpiCard';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusBadge } from '@/components/common/StatusBadge';
import { useReviewList } from '@/features/review/hooks/useReviewList';
import type { Review, ReviewListParams, ReviewStatus } from '@/features/review/types';

/**
 * SCR-REVIEW-001 검수 대기 목록 (V1.x mock 시각 정합).
 *
 * UI/UX §4-9 정합:
 * - KPI 4종 (검수 대기 / 검수중 / 승인 / 반려)
 * - 검색·상태 필터 (영상명·작업자명)
 * - DataTable 컬럼: 작업자/제출일/라벨 수 + [검수 시작 ▶] / [이어서 검수] / [결과 보기]
 *
 * 보안:
 * - URL searchParams로 페이지 파라미터만 전달 (axios 자동 URL 인코딩 — XSS 방어).
 * - REVIEWER 역할 검증은 라우터 RoleGuard에서 (이중 검증 — BE도 동일).
 */
const STATUS_OPTIONS: { value: '' | ReviewStatus; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'REVIEW_PENDING', label: '검수 대기' },
  { value: 'REVIEWING', label: '검수중' },
  { value: 'COMPLETED', label: '승인' },
  { value: 'REJECTED', label: '반려' },
];

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
  // Phase 1 — BE 가 ReviewResponse.eventName 을 직접 응답하므로 useVideos left-join 제거.
  // 영상 수 만 건 이상에서도 검수 목록은 BE 페이징 호출 1건만으로 동작.

  // 클라이언트 검색·상태 필터 (URL과 분리한 로컬 state — 서버 파라미터화는 후속)
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<'' | ReviewStatus>('');

  const updateParams = (next: Partial<ReviewListParams>) => {
    const sp = new URLSearchParams(searchParams);
    Object.entries({ ...params, ...next }).forEach(([k, v]) => {
      if (v === undefined || v === null || v === '') sp.delete(k);
      else sp.set(k, String(v));
    });
    setSearchParams(sp, { replace: false });
  };

  // ASSIGNED 상태(라벨링 작업 진행 중)는 검수 워크플로우 진입 전이므로 검수 목록에서 제외.
  // 검수 목록은 'REVIEW_PENDING'(라벨링 제출 완료) 이후 단계만 노출.
  const allRows = (data?.content ?? []).filter(
    (r) => r.status !== ('ASSIGNED' as ReviewStatus),
  );

  // KPI 집계 — 현재 페이지 데이터 기준 (전체 집계는 후속 BE endpoint에서)
  const kpi = useMemo(() => {
    return {
      pending: allRows.filter((r) => r.status === 'REVIEW_PENDING').length,
      reviewing: allRows.filter((r) => r.status === 'REVIEWING').length,
      completed: allRows.filter((r) => r.status === 'COMPLETED').length,
      rejected: allRows.filter((r) => r.status === 'REJECTED').length,
    };
  }, [allRows]);

  // 클라이언트 검색·상태 필터 적용
  const rows = useMemo(() => {
    return allRows.filter((r) => {
      if (statusFilter && r.status !== statusFilter) return false;
      if (keyword) {
        const q = keyword.toLowerCase();
        if (
          !r.cctvName.toLowerCase().includes(q) &&
          !r.workerName.toLowerCase().includes(q)
        ) {
          return false;
        }
      }
      return true;
    });
  }, [allRows, keyword, statusFilter]);

  const actionLabel = (status: ReviewStatus) => {
    if (status === 'REVIEW_PENDING') return '검수시작 ▶';
    if (status === 'REVIEWING') return '이어서 검수';
    return '결과보기 ▶';
  };

  const actionVariant = (status: ReviewStatus): 'primary' | 'secondary' => {
    if (status === 'REVIEW_PENDING') return 'primary';
    // 검수중 / 완료(승인·반려) 모두 secondary로 통일 — 동일 위치의 다른 버튼과 시각 일관성 확보
    return 'secondary';
  };

  // columns: navigate closure 캡처 — eventName 은 row(r) 에서 직접 사용하므로 deps 불필요
  const columns = useMemo<DataTableColumn<Review>[]>(
    () => [
      {
        key: 'cctvName',
        header: '영상명',
        render: (r) => (
          <div className="min-w-[160px]">
            <p className="truncate max-w-[200px] text-sm font-medium text-gray-800">
              {r.cctvName}
            </p>
            <p className="text-xs text-gray-400">{`video-${String(r.videoId).padStart(4, '0')}`}</p>
          </div>
        ),
      },
      {
        key: 'eventName',
        header: '이벤트',
        render: (r) =>
          r.eventName ? (
            <EventTypeBadge eventType={r.eventName} />
          ) : (
            <span className="text-xs text-gray-400">-</span>
          ),
      },
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
            variant={actionVariant(r.status)}
            size="sm"
            onClick={() => navigate(`/review/${r.id}`)}
            aria-label={`검수 시작 ${r.cctvName}`}
          >
            {actionLabel(r.status)}
          </Button>
        ),
      },
    ],
    [navigate],
  );

  const handleResetFilters = () => {
    setKeyword('');
    setStatusFilter('');
  };

  const isFilterActive = keyword !== '' || statusFilter !== '';

  return (
    <section className="flex flex-col gap-4" data-testid="review-list-page">
      <PageHeader
        title="검수 목록"
        description="작업자가 제출한 라벨링 결과를 검수합니다."
      />

      {/* KPI 4종 */}
      <div className="grid grid-cols-4 gap-4">
        <KpiCard
          label="검수 대기"
          value={kpi.pending}
          icon={<Hourglass size={22} className="text-yellow-600" aria-hidden />}
          iconBgClassName="bg-yellow-50"
        />
        <KpiCard
          label="검수중"
          value={kpi.reviewing}
          icon={<ClipboardCheck size={22} className="text-primary-600" aria-hidden />}
          iconBgClassName="bg-primary-50"
        />
        <KpiCard
          label="승인"
          value={kpi.completed}
          icon={<CheckCircle2 size={22} className="text-green-600" aria-hidden />}
          iconBgClassName="bg-green-50"
        />
        <KpiCard
          label="반려"
          value={kpi.rejected}
          icon={<XCircle size={22} className="text-red-600" aria-hidden />}
          iconBgClassName="bg-red-50"
        />
      </div>

      {/* 검색·상태 필터 */}
      <div className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white p-3">
        <div className="flex-1 min-w-[180px]">
          <label
            htmlFor="review-search"
            className="mb-1 block text-sub font-medium text-gray-500"
          >
            영상명 / 작업자명
          </label>
          <input
            id="review-search"
            type="text"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="검색어를 입력하세요"
            className="w-full rounded-md border border-gray-300 px-3 py-1.5 text-body focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500"
          />
        </div>
        <div className="min-w-[140px]">
          <label
            htmlFor="review-status-filter"
            className="mb-1 block text-sub font-medium text-gray-500"
          >
            상태
          </label>
          <select
            id="review-status-filter"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as '' | ReviewStatus)}
            className="w-full rounded-md border border-gray-300 bg-white px-3 py-1.5 text-body focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500"
          >
            {STATUS_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
        <div className="flex items-end gap-2">
          <Button variant="primary" size="sm" onClick={() => { /* 즉시 필터 적용은 onChange 기반 — 버튼은 명시적 트리거 용도 */ }}>
            조회
          </Button>
          <Button variant="ghost" size="sm" onClick={handleResetFilters} disabled={!isFilterActive}>
            초기화
          </Button>
        </div>
      </div>

      {error && <ErrorState title="검수 목록을 불러올 수 없습니다" />}

      <DataTable<Review>
        columns={columns}
        rows={rows}
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
