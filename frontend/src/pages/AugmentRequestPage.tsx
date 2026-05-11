import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertCircle,
  History,
  Info,
  RotateCcw,
  Search,
  Wand2,
  X,
} from 'lucide-react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { AugmentTypeCard } from '@/features/augment/components/AugmentTypeCard';
import { JobCard } from '@/features/augment/components/JobCard';
import { useRequestAugment } from '@/features/augment/hooks/useAugmentDecision';
import { useAugmentJobs } from '@/features/augment/hooks/useAugmentJobs';
import { AugmentType, type AugmentType as AT } from '@/features/augment/types';
import { useVideos } from '@/features/video/hooks/useVideos';
import { useUiStore } from '@/stores/useUiStore';

const ALL_TYPES: AT[] = [
  AugmentType.WINTER,
  AugmentType.NIGHT,
  AugmentType.RAIN,
  AugmentType.RESOLUTION,
];

const PAGE_SIZE = 20;

interface VideoFilterValues {
  q: string;
  eventType: string;
}

const DEFAULT_FILTERS: VideoFilterValues = {
  q: '',
  eventType: '',
};

/**
 * SCR-AUG-001 데이터 증강 요청 (`/augment`).
 *
 * UI/UX §4-12 + V1.x:
 * - 증강 유형 4종 카드(겨울/야간/비/해상도)
 * - **검수 완료된 영상만 선택 가능** — 비활성/검색·이벤트 필터·페이징
 * - 비상시 ID 콤마 입력 fallback
 * - 최근 요청 이력 잡 카드 6건 그리드
 *
 * 보안:
 * - REVIEWER 역할 검증 (라우터 + BE).
 * - videoIds는 number[]로 변환 후 전달 — 비숫자 입력 차단.
 * - types는 enum allowlist로 강제 (체크박스).
 */
export function AugmentRequestPage() {
  const navigate = useNavigate();
  const pushToast = useUiStore((s) => s.pushToast);

  const [selectedTypes, setSelectedTypes] = useState<Set<AT>>(new Set());
  const [selectedVideoIds, setSelectedVideoIds] = useState<Set<number>>(
    new Set(),
  );

  // 필터: localFilters (입력 중), filters (적용된 값)
  const [localFilters, setLocalFilters] =
    useState<VideoFilterValues>(DEFAULT_FILTERS);
  const [filters, setFilters] = useState<VideoFilterValues>(DEFAULT_FILTERS);
  const [page, setPage] = useState(0);

  // 검수 완료(승인) 영상만 — V1.x SFR-07 가드
  const { data: videosPage, isLoading: videosLoading } = useVideos({
    size: 999,
  });
  const allVideos = videosPage?.content ?? [];
  const approvedVideos = useMemo(
    () => allVideos.filter((v) => v.status === 'COMPLETED'),
    [allVideos],
  );

  const eventTypeOptions = useMemo(() => {
    const set = new Set<string>();
    approvedVideos.forEach((v) => {
      if (v.eventName) set.add(v.eventName);
    });
    return Array.from(set).sort();
  }, [approvedVideos]);

  const filteredVideos = useMemo(() => {
    let result = approvedVideos;
    if (filters.q) {
      const q = filters.q.toLowerCase();
      result = result.filter(
        (v) =>
          v.cctvName.toLowerCase().includes(q) ||
          String(v.id).includes(q) ||
          (v.eventName ?? '').toLowerCase().includes(q),
      );
    }
    if (filters.eventType) {
      result = result.filter((v) => v.eventName === filters.eventType);
    }
    return result;
  }, [approvedVideos, filters]);

  const totalPages = Math.max(1, Math.ceil(filteredVideos.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const pagedVideos = useMemo(
    () =>
      filteredVideos.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE),
    [filteredVideos, safePage],
  );

  // 필터 적용 시 페이지 리셋
  useEffect(() => {
    setPage(0);
  }, [filters]);

  const { data, isLoading, error } = useAugmentJobs({ page: 0, size: 6 });
  const { mutate, isPending } = useRequestAugment({
    onSuccess: (resp) => {
      pushToast({ variant: 'success', message: '증강 요청 등록됨' });
      setSelectedTypes(new Set());
      setSelectedVideoIds(new Set());
      if (resp?.jobId) {
        navigate(`/augment/result/${resp.jobId}`);
      }
    },
    onError: () => {
      pushToast({ variant: 'error', message: '증강 요청 실패' });
    },
  });

  const finalVideoIds = useMemo(() => {
    return Array.from(selectedVideoIds);
  }, [selectedVideoIds]);

  const canSubmit =
    finalVideoIds.length > 0 && selectedTypes.size > 0 && !isPending;

  const toggleType = (t: AT) => {
    setSelectedTypes((prev) => {
      const next = new Set(prev);
      if (next.has(t)) next.delete(t);
      else next.add(t);
      return next;
    });
  };

  const toggleVideo = (id: number) => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const pagedIds = pagedVideos.map((v) => v.id);
  const allPagedSelected =
    pagedIds.length > 0 && pagedIds.every((id) => selectedVideoIds.has(id));
  const somePagedSelected = pagedIds.some((id) => selectedVideoIds.has(id));

  const togglePagedAll = () => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (allPagedSelected) {
        pagedIds.forEach((id) => next.delete(id));
      } else {
        pagedIds.forEach((id) => next.add(id));
      }
      return next;
    });
  };

  const handleApplyFilters = (e: React.FormEvent) => {
    e.preventDefault();
    setFilters(localFilters);
  };

  const handleResetFilters = () => {
    setLocalFilters(DEFAULT_FILTERS);
    setFilters(DEFAULT_FILTERS);
  };

  const handleSubmit = () => {
    if (!canSubmit) return;
    mutate({
      videoIds: finalVideoIds,
      types: Array.from(selectedTypes),
    });
  };

  const totalEstimated = selectedTypes.size * finalVideoIds.length;

  return (
    <section
      className="flex flex-col gap-6 pb-28"
      data-testid="augment-request-page"
    >
      <PageHeader
        title="데이터 증강 요청"
        description="검수 완료(승인) 영상에 4종(겨울/야간/비/해상도) 증강을 요청합니다."
      />

      {/* SFR-07 안내 */}
      <div className="flex items-start gap-2 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2.5">
        <Info size={14} className="mt-0.5 shrink-0 text-blue-600" aria-hidden />
        <p className="text-xs text-blue-700">
          증강 요청은 검수 완료(승인)된 영상만 가능합니다. 미승인 영상은 목록에 표시되지 않습니다.
        </p>
      </div>

      {/* Step 1: 증강 유형 선택 */}
      <section className="space-y-4">
        <div className="flex items-center gap-2">
          <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary-600 text-xs font-bold text-white">
            1
          </span>
          <h2 className="text-base font-semibold text-gray-800">증강 유형 선택</h2>
          {selectedTypes.size > 0 && (
            <span className="inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700">
              {selectedTypes.size}종 선택
            </span>
          )}
        </div>

        <div
          className="grid grid-cols-2 gap-4 md:grid-cols-4"
          data-testid="augment-type-list"
        >
          {ALL_TYPES.map((t) => (
            <AugmentTypeCard
              key={t}
              type={t}
              selected={selectedTypes.has(t)}
              onToggle={() => toggleType(t)}
            />
          ))}
        </div>

        {selectedTypes.size === 0 && (
          <p className="flex items-center gap-1.5 text-xs text-amber-600">
            <AlertCircle size={13} aria-hidden />
            증강 유형을 하나 이상 선택하세요.
          </p>
        )}
      </section>

      {/* Step 2: 대상 영상 선택 */}
      <section className="space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary-600 text-xs font-bold text-white">
            2
          </span>
          <h2 className="text-base font-semibold text-gray-800">대상 영상 선택</h2>
          <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
            검수 완료 {approvedVideos.length}건
          </span>
          {selectedVideoIds.size > 0 && (
            <span className="inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700">
              {selectedVideoIds.size}건 선택
            </span>
          )}
        </div>

        {/* 검색·이벤트 필터 */}
        <form
          onSubmit={handleApplyFilters}
          className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white px-4 py-3 shadow-sm"
          data-testid="augment-video-filters"
        >
          <div className="min-w-[200px] flex-1">
            <label
              htmlFor="aug-video-q"
              className="mb-1 block text-xs font-medium text-gray-500"
            >
              영상명 / CCTV / ID
            </label>
            <div className="relative">
              <Search
                size={14}
                className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400"
                aria-hidden
              />
              <input
                id="aug-video-q"
                type="text"
                value={localFilters.q}
                onChange={(e) =>
                  setLocalFilters((p) => ({ ...p, q: e.target.value }))
                }
                placeholder="검색어 입력"
                className="w-full rounded-md border border-gray-300 bg-white py-1.5 pl-8 pr-3 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
              />
            </div>
          </div>
          <div>
            <label
              htmlFor="aug-event-filter"
              className="mb-1 block text-xs font-medium text-gray-500"
            >
              이벤트
            </label>
            <select
              id="aug-event-filter"
              value={localFilters.eventType}
              onChange={(e) =>
                setLocalFilters((p) => ({ ...p, eventType: e.target.value }))
              }
              className="rounded-md border border-gray-300 bg-white px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              <option value="">전체</option>
              {eventTypeOptions.map((et) => (
                <option key={et} value={et}>
                  {et}
                </option>
              ))}
            </select>
          </div>
          <div className="flex items-end gap-2">
            <Button type="submit" variant="primary" size="sm">
              <Search size={14} aria-hidden />
              조회
            </Button>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={handleResetFilters}
            >
              <RotateCcw size={14} aria-hidden />
              초기화
            </Button>
          </div>
        </form>

        {/* 영상 테이블 */}
        {videosLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} height={32} />
            ))}
          </div>
        ) : pagedVideos.length === 0 ? (
          <EmptyState
            message={
              approvedVideos.length === 0
                ? '검수 완료된 영상이 없습니다.'
                : '검색 조건에 맞는 영상이 없습니다.'
            }
          />
        ) : (
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
            <table
              className="w-full text-sm"
              data-testid="augment-video-table"
            >
              <thead className="bg-gray-50">
                <tr>
                  <th className="w-10 px-3 py-2">
                    <input
                      type="checkbox"
                      aria-label="현재 페이지 전체 선택"
                      checked={allPagedSelected}
                      ref={(el) => {
                        if (el)
                          el.indeterminate =
                            !allPagedSelected && somePagedSelected;
                      }}
                      onChange={togglePagedAll}
                      className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                    />
                  </th>
                  <th className="px-3 py-2 text-left text-xs font-medium text-gray-600">
                    영상명 / CCTV
                  </th>
                  <th className="px-3 py-2 text-left text-xs font-medium text-gray-600">
                    이벤트
                  </th>
                  <th className="px-3 py-2 text-left text-xs font-medium text-gray-600">
                    녹화일
                  </th>
                  <th className="px-3 py-2 text-left text-xs font-medium text-gray-600">
                    검수 완료 일시
                  </th>
                </tr>
              </thead>
              <tbody>
                {pagedVideos.map((v) => {
                  const checked = selectedVideoIds.has(v.id);
                  return (
                    <tr
                      key={v.id}
                      className="cursor-pointer border-b border-gray-100 hover:bg-gray-50"
                      onClick={() => toggleVideo(v.id)}
                    >
                      <td className="px-3 py-2">
                        <input
                          type="checkbox"
                          aria-label={`${v.cctvName} 선택`}
                          checked={checked}
                          onChange={() => toggleVideo(v.id)}
                          onClick={(e) => e.stopPropagation()}
                          className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                        />
                      </td>
                      <td className="max-w-[260px] px-3 py-2">
                        <p className="truncate text-sm font-medium text-gray-800">
                          {v.cctvName}
                        </p>
                        <p className="truncate font-mono text-xs text-gray-400">
                          #{v.id}
                        </p>
                      </td>
                      <td className="px-3 py-2">
                        {v.eventName ? (
                          <EventTypeBadge eventType={v.eventName} />
                        ) : (
                          <span className="text-xs text-gray-400">-</span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-xs text-gray-500">
                        {new Date(v.capturedAt).toLocaleDateString('ko-KR')}
                      </td>
                      <td className="px-3 py-2 text-xs text-gray-500">
                        {(() => {
                          const ts = (v as { updatedAt?: string }).updatedAt;
                          return ts
                            ? new Date(ts).toLocaleString('ko-KR', {
                                year: 'numeric',
                                month: '2-digit',
                                day: '2-digit',
                                hour: '2-digit',
                                minute: '2-digit',
                              })
                            : '-';
                        })()}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {totalPages > 1 && (
              <div className="flex items-center justify-between bg-gray-50 px-3 py-2 text-xs text-gray-500">
                <span>
                  전체 {filteredVideos.length}건 ({safePage + 1}/{totalPages}{' '}
                  페이지)
                </span>
                <div className="flex gap-1">
                  <button
                    type="button"
                    onClick={() => setPage(safePage - 1)}
                    disabled={safePage === 0}
                    className="rounded px-2 py-1 hover:bg-gray-200 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    이전
                  </button>
                  <button
                    type="button"
                    onClick={() => setPage(safePage + 1)}
                    disabled={safePage >= totalPages - 1}
                    className="rounded px-2 py-1 hover:bg-gray-200 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    다음
                  </button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* 선택 액션 배지 */}
        {selectedVideoIds.size > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-primary-200 bg-primary-50 px-4 py-3">
            <div className="flex items-center gap-3">
              <span className="inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700">
                {selectedVideoIds.size}개 영상 선택됨
              </span>
              <button
                type="button"
                onClick={() => setSelectedVideoIds(new Set())}
                className="inline-flex items-center gap-1 text-xs text-gray-500 underline hover:text-gray-700"
              >
                <X size={12} aria-hidden />
                선택 해제
              </button>
            </div>
          </div>
        )}

      </section>

      {/* 최근 요청 이력 */}
      <section
        aria-label="최근 요청 이력"
        className="flex flex-col gap-3"
      >
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <History size={18} className="text-gray-500" aria-hidden />
            <h2 className="text-base font-semibold text-gray-800">
              최근 요청 이력
            </h2>
          </div>
          {data && (
            <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
              {data.totalElements}건
            </span>
          )}
        </div>
        {error && <ErrorState title="이력을 불러올 수 없습니다" />}
        {isLoading && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} height={92} />
            ))}
          </div>
        )}
        {data &&
          (data.content.length === 0 ? (
            <EmptyState message="등록된 증강 요청이 없습니다" />
          ) : (
            <div
              data-testid="job-card-grid"
              className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3"
            >
              {data.content.slice(0, 6).map((job) => (
                <JobCard key={job.jobId} job={job} />
              ))}
            </div>
          ))}
      </section>

      {/* 고정 하단 액션 바 */}
      <div className="fixed bottom-0 left-60 right-0 z-20 border-t border-gray-200 bg-white px-6 py-4 shadow-[0_-2px_8px_rgba(0,0,0,0.04)]">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4">
          <div className="text-sm text-gray-600">
            선택:{' '}
            <span className="font-semibold text-primary-600">
              {selectedTypes.size}종
            </span>{' '}
            ×{' '}
            <span className="font-semibold text-primary-600">
              {finalVideoIds.length}건
            </span>{' '}
            = 예상{' '}
            <span className="font-bold text-gray-900">{totalEstimated}건</span>
          </div>
          <div className="flex items-center gap-3">
            <Button
              variant="secondary"
              size="md"
              onClick={() => navigate(-1)}
              disabled={isPending}
            >
              취소
            </Button>
            <Button
              data-testid="augment-submit"
              variant="primary"
              size="md"
              disabled={!canSubmit}
              loading={isPending}
              onClick={handleSubmit}
            >
              <Wand2 size={14} aria-hidden />
              증강 요청
            </Button>
          </div>
        </div>
      </div>
    </section>
  );
}
