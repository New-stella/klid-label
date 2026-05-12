import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, RefreshCw } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { PrivacyBadge } from '@/components/common/PrivacyBadge';
import { Skeleton } from '@/components/common/Skeleton';
import { StageBadge } from '@/components/common/StageBadge';
import { StatusBadge } from '@/components/common/StatusBadge';
import { cn } from '@/lib/cn';
import { VideoFilters } from '@/features/video/components/VideoFilters';
import { useVideos } from '@/features/video/hooks/useVideos';
import {
  parseVideoListParams,
  videoListParamsToSearchParams,
} from '@/features/video/parseVideoListParams';
import type { VideoListParams } from '@/features/video/types';

function formatDuration(seconds: number | undefined): string {
  if (!seconds) return '-';
  if (seconds >= 3600) {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    return `${h}시간 ${m}분`;
  }
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}분 ${s}초`;
}

/**
 * SCR-VIDEO-001 영상 처리 현황 (mock 정합).
 *
 * 컬럼: checkbox / CCTV명 / 이벤트 / 녹화일 / 길이 / 개인정보 / 처리단계 / 액션
 */
export function VideoListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const params = useMemo(() => parseVideoListParams(searchParams), [searchParams]);
  const { data, isLoading, error, refetch } = useVideos(params);
  const [selected, setSelected] = useState<Set<number>>(new Set());

  const updateParams = (next: VideoListParams) => {
    const sp = videoListParamsToSearchParams({ ...params, ...next });
    setSearchParams(sp, { replace: false });
    setSelected(new Set());
  };

  const rows = data?.content ?? [];
  const allChecked = rows.length > 0 && rows.every((r) => selected.has(r.id));
  const toggleAll = () => {
    if (allChecked) setSelected(new Set());
    else setSelected(new Set(rows.map((r) => r.id)));
  };
  const toggleRow = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleRefresh = () => {
    refetch();
    queryClient.invalidateQueries({ queryKey: ['videos'] });
  };

  const totalPages = Math.max(
    1,
    data ? Math.ceil(data.totalElements / (params.size ?? 20)) : 1,
  );
  const currentPage = data?.number ?? params.page ?? 0;

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-900">영상 처리 현황</h1>
          <p className="text-xs text-gray-500 mt-0.5">
            관제서버에서 인계받은 영상의 배치 처리 상태와 단계를 확인합니다.
          </p>
        </div>
        <Button variant="secondary" size="sm" onClick={handleRefresh}>
          <RefreshCw size={14} aria-hidden />
          새로고침
        </Button>
      </div>

      {/* Filters */}
      <VideoFilters initial={params} onApply={updateParams} />

      {error && <ErrorState title="영상 목록을 불러올 수 없습니다" />}

      {/* Bulk action bar */}
      {selected.size > 0 && (
        <div className="flex items-center gap-3 bg-primary-50 border border-primary-200 rounded-lg px-4 py-2.5 text-sm">
          <span className="font-medium text-primary-700">선택 {selected.size}건</span>
        </div>
      )}

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-lg shadow-sm overflow-hidden">
        <div className="flex items-center px-4 py-2 border-b border-gray-100 bg-gray-50">
          <input
            type="checkbox"
            checked={allChecked}
            onChange={toggleAll}
            className="w-4 h-4 accent-primary-600"
            aria-label="전체 선택"
          />
          <span className="ml-2 text-xs text-gray-500">
            전체 {data?.totalElements ?? 0}건
            {data ? ` (${currentPage + 1}/${totalPages} 페이지)` : ''}
          </span>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50">
                <th
                  className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3"
                  style={{ width: '40px' }}
                >
                  {''}
                </th>
                <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  CCTV명
                </th>
                <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  이벤트
                </th>
                <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  녹화일
                </th>
                <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  길이
                </th>
                <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  개인정보
                </th>
                <th
                  className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3"
                  style={{ width: '120px' }}
                >
                  처리 단계
                </th>
                <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  액션
                </th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i} className="border-b border-gray-100">
                    {Array.from({ length: 8 }).map((__, j) => (
                      <td key={j} className="px-4 py-3">
                        <Skeleton height={16} />
                      </td>
                    ))}
                  </tr>
                ))
              ) : rows.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-3 py-12">
                    <EmptyState message="해당하는 영상이 없습니다." />
                  </td>
                </tr>
              ) : (
                rows.map((v) => (
                  <tr
                    key={v.id}
                    className={cn(
                      'border-b border-gray-100 transition-colors hover:bg-primary-50 cursor-pointer',
                      selected.has(v.id) && 'bg-primary-50',
                    )}
                    onClick={() => navigate(`/video/${v.id}`)}
                  >
                    <td
                      className="px-4 py-3"
                      onClick={(e) => e.stopPropagation()}
                    >
                      <input
                        type="checkbox"
                        checked={selected.has(v.id)}
                        onChange={() => toggleRow(v.id)}
                        className="w-4 h-4 accent-primary-600"
                        aria-label={`${v.cctvName} 선택`}
                      />
                    </td>
                    <td className="px-4 py-3">
                      <span className="font-medium text-gray-800 text-xs">{v.cctvName}</span>
                    </td>
                    <td className="px-4 py-3">
                      <EventTypeBadge eventType={v.eventTypeCd ?? v.eventName ?? ''} />
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs text-gray-500">
                        {v.capturedAt ? v.capturedAt.slice(0, 10) : '-'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs">{formatDuration(v.durationSec)}</span>
                    </td>
                    <td className="px-4 py-3">
                      <PrivacyBadge privacyType={v.privacyTypeCd ?? ''} size="sm" />
                    </td>
                    <td className="px-4 py-3">
                      {v.status === 'COMPLETED' ? (
                        <StageBadge stage="COMPLETED" status="COMPLETED" />
                      ) : v.status === 'FAILED' ? (
                        <StageBadge stage="FAILED" status="FAILED" />
                      ) : (
                        <StatusBadge status={v.status} />
                      )}
                    </td>
                    <td
                      className="px-4 py-3"
                      onClick={(e) => e.stopPropagation()}
                    >
                      <div className="flex gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => navigate(`/video/${v.id}`)}
                        >
                          상세
                          <ChevronRight size={12} aria-hidden />
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Pagination */}
      {data && totalPages > 1 && (
        <div className="flex items-center justify-center gap-1 mt-4">
          <button
            type="button"
            onClick={() => updateParams({ page: currentPage - 1 })}
            disabled={currentPage === 0}
            aria-label="이전 페이지"
            className="inline-flex items-center justify-center w-8 h-8 rounded-md text-gray-500 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          >
            <ChevronLeft size={16} aria-hidden />
          </button>
          {Array.from({ length: Math.min(totalPages, 7) }).map((_, i) => {
            const p = i;
            return (
              <button
                key={p}
                type="button"
                onClick={() => updateParams({ page: p })}
                aria-current={p === currentPage ? 'page' : undefined}
                className={cn(
                  'inline-flex items-center justify-center w-8 h-8 rounded-md text-sm font-medium transition-colors',
                  p === currentPage
                    ? 'bg-primary-600 text-white'
                    : 'text-gray-600 hover:bg-gray-100',
                )}
              >
                {p + 1}
              </button>
            );
          })}
          <button
            type="button"
            onClick={() => updateParams({ page: currentPage + 1 })}
            disabled={currentPage >= totalPages - 1}
            aria-label="다음 페이지"
            className="inline-flex items-center justify-center w-8 h-8 rounded-md text-gray-500 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          >
            <ChevronRight size={16} aria-hidden />
          </button>
        </div>
      )}
    </div>
  );
}
