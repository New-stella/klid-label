import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ShieldOff, CheckCircle2, XCircle, AlertTriangle, RefreshCw } from 'lucide-react';
import { useFetch } from '../../api/queries';
import { api } from '../../api/client';
import type { DeidentDto, Page, PrivacyType } from '../../api/types';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Pagination } from '../../components/ui/Pagination';
import { Skeleton } from '../../components/ui/Skeleton';
import { StatCard } from '../../components/ui/StatCard';
import { useToast } from '../../components/common/Toast';

type DeidentStatus = DeidentDto['status'];

const PAGE_SIZE = 15;

const PRIVACY_LABEL: Record<PrivacyType, string> = {
  PRVC: '개인영상',
  PSDO: '가명처리',
  ANONY: '익명화',
};

const PRIVACY_TONE: Record<PrivacyType, 'info' | 'warning' | 'neutral'> = {
  PRVC: 'info',
  PSDO: 'warning',
  ANONY: 'neutral',
};

const STATUS_LABEL: Record<DeidentStatus, string> = {
  SUCCESS: '성공',
  FAIL: '실패',
  PENDING: '처리중',
  'N/A': 'N/A',
};

const STATUS_TONE: Record<DeidentStatus, 'success' | 'danger' | 'info' | 'neutral'> = {
  SUCCESS: 'success',
  FAIL: 'danger',
  PENDING: 'info',
  'N/A': 'neutral',
};

export function DeidentList() {
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [statusFilter, setStatusFilter] = useState<DeidentStatus | ''>('');
  const [privacyFilter, setPrivacyFilter] = useState<PrivacyType | ''>('');
  const [retryingIds, setRetryingIds] = useState<Set<string>>(new Set());

  const params: Record<string, unknown> = { page, size: PAGE_SIZE };
  if (statusFilter) params['status'] = statusFilter;
  if (privacyFilter) params['privacyType'] = privacyFilter;
  if (search) params['videoName'] = search;

  const { data, isLoading, refetch } = useFetch<Page<DeidentDto>>('/deident', params);

  const allStatuses = useFetch<Page<DeidentDto>>('/deident', { page: 0, size: 9999 });
  const allItems = allStatuses.data?.content ?? [];

  const successCount = allItems.filter((d) => d.status === 'SUCCESS').length;
  const failCount = allItems.filter((d) => d.status === 'FAIL').length;
  const naCount = allItems.filter((d) => d.status === 'N/A').length;

  const handleSearch = () => {
    setSearch(searchInput);
    setPage(0);
  };

  const handleReset = () => {
    setSearchInput('');
    setSearch('');
    setStatusFilter('');
    setPrivacyFilter('');
    setPage(0);
  };

  const handleRetry = async (id: string) => {
    setRetryingIds((prev) => new Set(prev).add(id));
    try {
      await api.post(`/deident/${id}/retry`);
      showToast('재처리 요청됨', 'success');
      refetch();
    } catch {
      showToast('재처리 요청 실패', 'error');
    } finally {
      setRetryingIds((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    }
  };

  const items = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-blue-50">
            <ShieldOff size={20} className="text-blue-600" />
          </div>
          <h1 className="text-xl font-bold text-gray-900">비식별화 결과</h1>
        </div>
        {failCount > 0 && (
          <Badge tone="danger" size="md">
            재처리 대상 {failCount}건
          </Badge>
        )}
      </div>

      {/* KPI */}
      <div className="grid grid-cols-3 gap-4">
        <StatCard
          label="처리 성공"
          value={allStatuses.isLoading ? '—' : successCount}
          icon={CheckCircle2}
          tone="success"
        />
        <StatCard
          label="처리 실패"
          value={allStatuses.isLoading ? '—' : failCount}
          icon={XCircle}
          tone="danger"
        />
        <StatCard
          label="개인정보 없음 (N/A)"
          value={allStatuses.isLoading ? '—' : naCount}
          icon={AlertTriangle}
          tone="warning"
        />
      </div>

      {/* Filter Bar */}
      <div className="bg-white border border-gray-200 rounded-lg p-4 flex flex-wrap items-end gap-3">
        <div className="flex-1 min-w-48">
          <label className="block text-xs font-medium text-gray-600 mb-1">영상명 검색</label>
          <input
            type="text"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleSearch();
            }}
            placeholder="영상명 입력"
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>

        <div className="min-w-36">
          <label className="block text-xs font-medium text-gray-600 mb-1">처리 상태</label>
          <select
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value as DeidentStatus | '');
              setPage(0);
            }}
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white"
          >
            <option value="">전체</option>
            <option value="SUCCESS">성공</option>
            <option value="FAIL">실패</option>
            <option value="PENDING">처리중</option>
            <option value="N/A">N/A</option>
          </select>
        </div>

        <div className="min-w-36">
          <label className="block text-xs font-medium text-gray-600 mb-1">개인정보유형</label>
          <select
            value={privacyFilter}
            onChange={(e) => {
              setPrivacyFilter(e.target.value as PrivacyType | '');
              setPage(0);
            }}
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white"
          >
            <option value="">전체</option>
            <option value="PRVC">PRVC (개인영상)</option>
            <option value="PSDO">PSDO (가명처리)</option>
            <option value="ANONY">ANONY (익명화)</option>
          </select>
        </div>

        <div className="flex gap-2">
          <Button variant="primary" size="sm" onClick={handleSearch}>
            조회
          </Button>
          <Button variant="secondary" size="sm" onClick={handleReset}>
            초기화
          </Button>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-200">
              <th className="text-left px-4 py-3 font-medium text-gray-600">영상명</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">개인정보유형</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">처리 상태</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">처리일</th>
              <th className="text-center px-4 py-3 font-medium text-gray-600">작업</th>
            </tr>
          </thead>
          <tbody>
            {isLoading
              ? Array.from({ length: 8 }, (_, i) => (
                  <tr key={i} className="border-b border-gray-100">
                    <td className="px-4 py-3">
                      <Skeleton height="1.1rem" width="60%" />
                    </td>
                    <td className="px-4 py-3">
                      <Skeleton height="1.1rem" width="4rem" />
                    </td>
                    <td className="px-4 py-3">
                      <Skeleton height="1.1rem" width="3.5rem" />
                    </td>
                    <td className="px-4 py-3">
                      <Skeleton height="1.1rem" width="7rem" />
                    </td>
                    <td className="px-4 py-3">
                      <Skeleton height="1.1rem" width="5rem" className="mx-auto" />
                    </td>
                  </tr>
                ))
              : items.map((item) => (
                  <tr key={item.id} className="border-b border-gray-100 hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 font-medium text-gray-800 max-w-56 truncate">
                      {item.videoName}
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={PRIVACY_TONE[item.privacyType]} size="sm">
                        {PRIVACY_LABEL[item.privacyType]}
                      </Badge>
                    </td>
                    <td className="px-4 py-3">
                      {item.status === 'N/A' ? (
                        <span className="text-xs text-gray-400">개인정보 없음 — 처리 불필요</span>
                      ) : (
                        <Badge tone={STATUS_TONE[item.status]} size="sm">
                          {STATUS_LABEL[item.status]}
                        </Badge>
                      )}
                    </td>
                    <td className="px-4 py-3 text-gray-500">
                      {item.processedAt ? new Date(item.processedAt).toLocaleString('ko-KR') : '—'}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-center gap-2">
                        {item.status === 'SUCCESS' && (
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => navigate(`/deident/${item.id}`)}
                          >
                            결과보기
                          </Button>
                        )}
                        {item.status === 'FAIL' && (
                          <Button
                            variant="secondary"
                            size="sm"
                            leftIcon={RefreshCw}
                            loading={retryingIds.has(item.id)}
                            onClick={() => handleRetry(item.id)}
                          >
                            재처리
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
            {!isLoading && items.length === 0 && (
              <tr>
                <td colSpan={5} className="text-center py-16 text-gray-400 text-sm">
                  조건에 맞는 비식별 결과가 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <Pagination page={page} totalPages={totalPages} onChange={setPage} />
      )}
    </div>
  );
}

export default DeidentList;
