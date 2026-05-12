import { useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { KpiCard } from '@/components/common/KpiCard';
import { Skeleton } from '@/components/common/Skeleton';
import { StageBadge } from '@/components/common/StageBadge';
import { cn } from '@/lib/cn';
import { useBatchStatus } from '@/features/video/hooks/useBatchStatus';
import type { BatchStageProgress } from '@/features/video/types';

function formatDateTime(value: string | null): string {
  if (!value) return '-';
  try {
    return new Date(value).toLocaleString('ko-KR');
  } catch {
    return value;
  }
}

/**
 * SCR-VIDEO-002 처리 현황 (mock BatchCompletedList 정합 — 테이블 레이아웃).
 *
 * 5초 폴링 + 표 기반 처리 현황. mock의 컬럼 구조(checkbox / 식별자 / 처리단계 / 시각 / 액션)를
 * 따르되, BatchStageProgress가 제공하는 필드(rawSn, stage, startedAt, lastUpdatedAt, retryCount,
 * errorMessage)만 표시한다.
 */
export function VideoStatusPage() {
  const { data, isLoading, error, refetch } = useBatchStatus();
  const queryClient = useQueryClient();
  const items = data?.items ?? [];
  const [selected, setSelected] = useState<Set<number>>(new Set());

  const totalProcessing = items.filter(
    (i) => !['COMPLETED', 'FAILED', 'PENDING'].includes(i.stage),
  ).length;
  const totalCompleted = items.filter((i) => i.stage === 'COMPLETED').length;
  const totalFailed = items.filter((i) => i.stage === 'FAILED').length;

  const allChecked = items.length > 0 && items.every((r) => selected.has(r.rawSn));
  const toggleAll = () => {
    if (allChecked) setSelected(new Set());
    else setSelected(new Set(items.map((r) => r.rawSn)));
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

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-900">처리 현황</h1>
          <p className="text-xs text-gray-500 mt-0.5">
            배치 파이프라인의 실시간 처리 현황 (5초마다 자동 갱신)
          </p>
        </div>
        <Button variant="secondary" size="sm" onClick={handleRefresh}>
          <RefreshCw size={14} aria-hidden />
          새로고침
        </Button>
      </div>

      {/* KPI */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <KpiCard label="처리 중" value={totalProcessing} unit="건" />
        <KpiCard label="완료" value={totalCompleted} unit="건" />
        <KpiCard label="실패" value={totalFailed} unit="건" />
      </div>

      {error && <ErrorState title="배치 현황을 불러올 수 없습니다" />}

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
          <span className="ml-2 text-xs text-gray-500">전체 {items.length}건</span>
        </div>

        <div className="overflow-x-auto">
          <table data-testid="batch-video-list" className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50">
                <th
                  className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3"
                  style={{ width: '40px' }}
                >
                  {''}
                </th>
                <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  원본 번호
                </th>
                <th
                  className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3"
                  style={{ width: '120px' }}
                >
                  처리 단계
                </th>
                <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  시작 시각
                </th>
                <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  마지막 갱신
                </th>
                <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  재시도
                </th>
                <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                  오류
                </th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i} className="border-b border-gray-100">
                    {Array.from({ length: 7 }).map((__, j) => (
                      <td key={j} className="px-4 py-3">
                        <Skeleton height={16} />
                      </td>
                    ))}
                  </tr>
                ))
              ) : items.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-3 py-12">
                    <EmptyState
                      title="처리 중인 영상이 없습니다"
                      message="배치 처리 대기 중이거나 모두 완료되었습니다."
                    />
                  </td>
                </tr>
              ) : (
                items.map((item: BatchStageProgress) => {
                  const status =
                    item.stage === 'COMPLETED'
                      ? 'COMPLETED'
                      : item.stage === 'FAILED'
                        ? 'FAILED'
                        : item.stage === 'PENDING'
                          ? 'PENDING'
                          : 'PROGRESS';
                  return (
                    <tr
                      key={item.rawSn}
                      className={cn(
                        'border-b border-gray-100 transition-colors hover:bg-primary-50',
                        selected.has(item.rawSn) && 'bg-primary-50',
                      )}
                    >
                      <td className="px-4 py-3">
                        <input
                          type="checkbox"
                          checked={selected.has(item.rawSn)}
                          onChange={() => toggleRow(item.rawSn)}
                          className="w-4 h-4 accent-primary-600"
                          aria-label={`원본 ${item.rawSn} 선택`}
                        />
                      </td>
                      <td className="px-4 py-3">
                        <span className="font-medium text-gray-800 text-xs">
                          #{item.rawSn}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <StageBadge stage={item.stage} status={status} />
                      </td>
                      <td className="px-4 py-3">
                        <span className="text-xs text-gray-500">
                          {formatDateTime(item.startedAt)}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className="text-xs text-gray-500">
                          {formatDateTime(item.lastUpdatedAt)}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className="text-xs text-gray-600">
                          {item.retryCount > 0 ? `${item.retryCount}회` : '-'}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={cn(
                            'text-xs',
                            item.errorMessage ? 'text-red-600' : 'text-gray-400',
                          )}
                          title={item.errorMessage ?? undefined}
                        >
                          {item.errorMessage
                            ? item.errorMessage.length > 30
                              ? `${item.errorMessage.slice(0, 30)}…`
                              : item.errorMessage
                            : '-'}
                        </span>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
