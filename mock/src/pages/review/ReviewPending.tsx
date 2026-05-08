import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ClipboardCheck, Hourglass, CheckCircle2, XCircle } from 'lucide-react';
import { useFetch } from '../../api/queries';
import { StatCard } from '../../components/ui/StatCard';
import { StatusBadge } from '../../components/batch/StatusBadge';
import { Pagination } from '../../components/ui/Pagination';
import type { Page, ReviewDto } from '../../api/types';

type ReviewStatus = ReviewDto['status'];

const PAGE_SIZE = 10;

const STATUS_OPTS: { value: '' | ReviewStatus; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'PENDING', label: '검수대기' },
  { value: 'IN_REVIEW', label: '검수중' },
  { value: 'APPROVED', label: '승인' },
  { value: 'REJECTED', label: '반려' },
];

export function ReviewPending() {
  const navigate = useNavigate();

  // Filter state
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'' | ReviewStatus>('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [appliedStatus, setAppliedStatus] = useState<'' | ReviewStatus>('');
  const [page, setPage] = useState(0);

  // Fetch all reviews (we filter client-side for full feature)
  const { data: reviewPage, isLoading, refetch } = useFetch<Page<ReviewDto>>(
    '/reviews',
    { status: appliedStatus || undefined, page, size: PAGE_SIZE },
  );

  // KPI from all reviews (separate fetch, no filter)
  const { data: allPage } = useFetch<Page<ReviewDto>>('/reviews', { size: 100 });
  const allReviews = allPage?.content ?? [];
  const kpi = {
    pending: allReviews.filter((r) => r.status === 'PENDING').length,
    inReview: allReviews.filter((r) => r.status === 'IN_REVIEW').length,
    approved: allReviews.filter((r) => r.status === 'APPROVED').length,
    rejected: allReviews.filter((r) => r.status === 'REJECTED').length,
  };

  // Client-side search filter on fetched page
  const reviews = (reviewPage?.content ?? []).filter((r) => {
    if (!appliedSearch) return true;
    const q = appliedSearch.toLowerCase();
    return r.videoName.toLowerCase().includes(q) || r.workerName.toLowerCase().includes(q);
  });

  const totalPages = reviewPage?.totalPages ?? 0;

  const handleSearch = () => {
    setAppliedSearch(search);
    setAppliedStatus(statusFilter);
    setPage(0);
    refetch();
  };

  const handleReset = () => {
    setSearch('');
    setStatusFilter('');
    setAppliedSearch('');
    setAppliedStatus('');
    setPage(0);
    refetch();
  };

  const handleRowAction = (review: ReviewDto) => {
    if (review.status === 'APPROVED' || review.status === 'REJECTED') {
      navigate(`/review/${review.id}?readonly=true`);
    } else {
      navigate(`/review/${review.id}`);
    }
  };

  const actionLabel = (status: ReviewStatus) => {
    if (status === 'PENDING') return '검수시작 ▶';
    if (status === 'IN_REVIEW') return '이어서 검수';
    return '결과보기 ▶';
  };

  const actionClass = (status: ReviewStatus) => {
    if (status === 'PENDING') return 'bg-blue-600 hover:bg-blue-700 text-white';
    if (status === 'IN_REVIEW') return 'bg-purple-600 hover:bg-purple-700 text-white';
    return 'bg-gray-100 hover:bg-gray-200 text-gray-700';
  };

  return (
    <div className="p-6 space-y-6">
      {/* Page header */}
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-gray-900">검수 목록</h1>
      </div>

      {/* KPI cards */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <StatCard
          label="검수 대기"
          value={kpi.pending}
          icon={Hourglass}
          tone="warning"
        />
        <StatCard
          label="검수중"
          value={kpi.inReview}
          icon={ClipboardCheck}
          tone="primary"
        />
        <StatCard
          label="승인"
          value={kpi.approved}
          icon={CheckCircle2}
          tone="success"
        />
        <StatCard
          label="반려"
          value={kpi.rejected}
          icon={XCircle}
          tone="danger"
        />
      </div>

      {/* Filter bar */}
      <div className="bg-white border border-gray-200 rounded-lg p-4">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex-1 min-w-[160px]">
            <label className="block text-xs text-gray-500 mb-1">영상명 / 작업자명</label>
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
              placeholder="검색어 입력"
              className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div className="min-w-[120px]">
            <label className="block text-xs text-gray-500 mb-1">상태</label>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as '' | ReviewStatus)}
              className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              {STATUS_OPTS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </div>
          <button
            onClick={handleSearch}
            className="px-4 py-1.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 transition-colors"
          >
            조회
          </button>
          <button
            onClick={handleReset}
            className="px-4 py-1.5 bg-gray-100 text-gray-700 text-sm rounded-lg hover:bg-gray-200 transition-colors"
          >
            초기화
          </button>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        {isLoading ? (
          <div className="flex items-center justify-center py-16 text-gray-400 text-sm">로드 중...</div>
        ) : reviews.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-gray-400">
            <ClipboardCheck size={40} className="mb-3 opacity-30" />
            <p className="text-sm">검수 작업이 없습니다</p>
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase">영상명</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase">작업자</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase">제출일</th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-500 uppercase">라벨 수</th>
                <th className="text-center px-4 py-3 text-xs font-semibold text-gray-500 uppercase">상태</th>
                <th className="text-center px-4 py-3 text-xs font-semibold text-gray-500 uppercase">액션</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {reviews.map((review) => (
                <tr key={review.id} className="hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-3">
                    <span className="font-medium text-gray-900 truncate max-w-[220px] block" title={review.videoName}>
                      {review.videoName}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-600">{review.workerName}</td>
                  <td className="px-4 py-3 text-gray-500 text-xs whitespace-nowrap">
                    {new Date(review.submittedAt).toLocaleDateString('ko-KR')}
                  </td>
                  <td className="px-4 py-3 text-right text-gray-700 tabular-nums">
                    {review.labelCount.toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-center">
                    <StatusBadge status={review.status} />
                  </td>
                  <td className="px-4 py-3 text-center">
                    <button
                      onClick={() => handleRowAction(review)}
                      className={[
                        'px-3 py-1 rounded-lg text-xs font-medium transition-colors whitespace-nowrap',
                        actionClass(review.status),
                      ].join(' ')}
                    >
                      {actionLabel(review.status)}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {/* Pagination */}
        {!isLoading && totalPages > 1 && (
          <div className="border-t border-gray-100 px-4 py-3">
            <Pagination
              page={page}
              totalPages={totalPages}
              onChange={(p) => { setPage(p); }}
            />
          </div>
        )}
      </div>
    </div>
  );
}
