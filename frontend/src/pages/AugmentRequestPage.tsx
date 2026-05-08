import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertCircle, Info, Wand2 } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { Input } from '@/components/common/Input';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { AugmentTypeCheckbox } from '@/features/augment/components/AugmentTypeCheckbox';
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

/**
 * SCR-AUG-001 데이터 증강 요청 (`/augment`).
 *
 * UI/UX §4-12 + V1.x:
 * - 증강 유형 4종 체크박스(겨울/야간/비/해상도)
 * - **검수 완료된 영상만 선택 가능** — 비활성/검색·이벤트 필터·페이징
 * - 비상시 ID 콤마 입력 fallback
 * - 최근 요청 이력 잡 카드 6건 그리드 (5초 폴링)
 *
 * 보안:
 * - REVIEWER 역할 검증 (라우터 + BE).
 * - videoIds는 number[]로 변환 후 전달 — 비숫자 입력 차단.
 * - types는 enum allowlist로 강제 (체크박스).
 */
export function AugmentRequestPage() {
  const navigate = useNavigate();
  const pushToast = useUiStore((s) => s.pushToast);

  const [videoIdInput, setVideoIdInput] = useState('');
  const [selectedTypes, setSelectedTypes] = useState<Set<AT>>(new Set());
  const [selectedVideoIds, setSelectedVideoIds] = useState<Set<number>>(
    new Set(),
  );
  const [search, setSearch] = useState('');
  const [eventFilter, setEventFilter] = useState('');
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
    if (search) {
      const q = search.toLowerCase();
      result = result.filter(
        (v) =>
          v.cctvName.toLowerCase().includes(q) ||
          String(v.id).includes(q) ||
          (v.eventName ?? '').toLowerCase().includes(q),
      );
    }
    if (eventFilter) {
      result = result.filter((v) => v.eventName === eventFilter);
    }
    return result;
  }, [approvedVideos, search, eventFilter]);

  const totalPages = Math.max(1, Math.ceil(filteredVideos.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const pagedVideos = useMemo(
    () =>
      filteredVideos.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE),
    [filteredVideos, safePage],
  );

  // 검색·필터 변경 시 페이지 리셋
  useEffect(() => {
    setPage(0);
  }, [search, eventFilter]);

  const { data, isLoading, error } = useAugmentJobs({ page: 0, size: 6 });
  const { mutate, isPending } = useRequestAugment({
    onSuccess: (resp) => {
      pushToast({ variant: 'success', message: '증강 요청 등록됨' });
      setVideoIdInput('');
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

  // 영상 선택 + 콤마 입력 합집합
  const parsedManualIds = useMemo(() => {
    return videoIdInput
      .split(',')
      .map((s) => Number.parseInt(s.trim(), 10))
      .filter((n) => Number.isFinite(n) && n > 0);
  }, [videoIdInput]);

  const finalVideoIds = useMemo(() => {
    const set = new Set<number>(selectedVideoIds);
    parsedManualIds.forEach((id) => set.add(id));
    return Array.from(set);
  }, [selectedVideoIds, parsedManualIds]);

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

  const handleSubmit = () => {
    if (!canSubmit) return;
    mutate({
      videoIds: finalVideoIds,
      types: Array.from(selectedTypes),
    });
  };

  return (
    <section className="flex flex-col gap-4" data-testid="augment-request-page">
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

      <section
        aria-label="증강 요청 폼"
        className="flex flex-col gap-3 rounded border border-border bg-white p-4"
      >
        {/* Step 1: 증강 유형 */}
        <div>
          <span className="mb-1 block text-body font-medium text-primary">
            증강 유형 (복수 선택)
          </span>
          <div className="flex flex-wrap gap-4" data-testid="augment-type-list">
            {ALL_TYPES.map((t) => (
              <AugmentTypeCheckbox
                key={t}
                type={t}
                checked={selectedTypes.has(t)}
                onChange={() => toggleType(t)}
              />
            ))}
          </div>
          {selectedTypes.size === 0 && (
            <p className="mt-2 flex items-center gap-1.5 text-xs text-amber-600">
              <AlertCircle size={13} aria-hidden />
              증강 유형을 하나 이상 선택하세요.
            </p>
          )}
        </div>

        {/* Step 2: 검수 완료 영상 선택 */}
        <div className="mt-2 flex flex-col gap-2">
          <div className="flex items-center justify-between gap-2">
            <span className="text-body font-medium text-primary">
              대상 영상 선택
              <span className="ml-2 inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
                검수 완료 {approvedVideos.length}건
              </span>
              {selectedVideoIds.size > 0 && (
                <span className="ml-1 inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700">
                  {selectedVideoIds.size}건 선택
                </span>
              )}
            </span>
          </div>

          {/* 검색·이벤트 필터 */}
          <div
            className="flex flex-wrap items-end gap-3 rounded-lg bg-gray-50 px-3 py-3"
            data-testid="augment-video-filters"
          >
            <div className="min-w-[180px] flex-1">
              <label
                htmlFor="aug-video-q"
                className="mb-1 block text-xs font-medium text-gray-500"
              >
                영상명 / CCTV / ID
              </label>
              <input
                id="aug-video-q"
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="검색어 입력"
                className="w-full rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
              />
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
                value={eventFilter}
                onChange={(e) => setEventFilter(e.target.value)}
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
          </div>

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
            <div className="overflow-hidden rounded-lg border border-gray-200">
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

          {/* ID 콤마 입력 (fallback) */}
          <details className="mt-2">
            <summary className="cursor-pointer text-xs text-gray-500 hover:text-gray-700">
              영상 ID 직접 입력 (보조)
            </summary>
            <div className="mt-2">
              <Input
                label="대상 영상 ID (콤마 구분)"
                placeholder="예: 1, 2, 3"
                value={videoIdInput}
                onChange={(e) => setVideoIdInput(e.target.value)}
                hint={
                  parsedManualIds.length > 0
                    ? `+${parsedManualIds.length}건 추가됨`
                    : undefined
                }
              />
            </div>
          </details>
        </div>

        <div className="mt-2 flex items-center justify-between border-t border-gray-100 pt-3">
          <span className="text-sm text-gray-600">
            선택{' '}
            <span className="font-semibold text-primary-600">
              {selectedTypes.size}종
            </span>{' '}
            ×{' '}
            <span className="font-semibold text-primary-600">
              {finalVideoIds.length}건
            </span>{' '}
            = 예상{' '}
            <span className="font-bold text-gray-900">
              {selectedTypes.size * finalVideoIds.length}건
            </span>
          </span>
          <Button
            data-testid="augment-submit"
            variant="primary"
            disabled={!canSubmit}
            loading={isPending}
            onClick={handleSubmit}
          >
            <Wand2 size={14} aria-hidden />
            증강 요청
          </Button>
        </div>
      </section>

      <section
        aria-label="최근 요청 이력"
        className="flex flex-col gap-3 rounded border border-border bg-white p-4"
      >
        <h2 className="text-section-title text-primary">
          최근 요청 이력 (5초 자동 갱신)
        </h2>
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
    </section>
  );
}
