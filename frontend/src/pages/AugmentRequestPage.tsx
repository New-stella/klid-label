import { useEffect, useMemo, useRef, useState } from 'react';
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
import { JobCard } from '@/features/augment/components/JobCard';
import { ProcessKindCard } from '@/features/augment/components/ProcessKindCard';
import { TargetResolutionSelect } from '@/features/augment/components/TargetResolutionSelect';
import { useRequestAugment } from '@/features/augment/hooks/useAugmentDecision';
import { useAugmentJobs } from '@/features/augment/hooks/useAugmentJobs';
import {
  PROCESS_KINDS,
  PROCESS_KIND_LABEL,
  isAugmentKind,
  type ProcessKind,
} from '@/features/augment/types';
import { useResolutionExport } from '@/features/video/hooks/useResolutionExport';
import { useVideos } from '@/features/video/hooks/useVideos';
import type { ResolutionPreset } from '@/features/video/types';
import { ApiError } from '@/lib/api/errors';
import { useUiStore } from '@/stores/useUiStore';

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
 * SCR-AUG-001 데이터 증강 요청 (`/augment`) — 통합 단일 선택 UI.
 *
 * UI/UX §4-12 + V1.x (Phase 1 통합 재구성):
 * - 처리 종류 카드 4개(겨울/야간/우천/해상도 변경)를 radiogroup 으로 **단일 선택**.
 *   증강 3종은 외부 위탁 잡, 해상도 변경(RESOLUTION)은 저작도구 직접 수행(SFR-06-03).
 * - 해상도 변경 종류를 고른 경우에만 타겟 해상도(1080P/720P/480P) 선택 UI 노출.
 * - 대상 영상은 **1건 단일 선택**(라디오).
 * - 종류를 바꾸면 타겟 해상도(preset)·결과 상태 초기화.
 * - **검수 완료된 영상만 선택 가능** — 비활성/검색·이벤트 필터·페이징.
 * - 최근 요청 이력 잡 카드 6건 그리드.
 *
 * Phase 1 범위는 선택 상태/렌더링까지. 실행(submit) 분기·API 호출은 Phase 2.
 *
 * 보안:
 * - REVIEWER 역할 검증 (라우터 + BE).
 * - videoId 는 number 로 변환 후 전달 — 비숫자 입력 차단.
 * - kind/preset 은 정의된 상수 집합(allowlist)으로만 좁힘 — 임의 문자열 분기 차단.
 */
export function AugmentRequestPage() {
  const navigate = useNavigate();
  const pushToast = useUiStore((s) => s.pushToast);

  const [selectedKind, setSelectedKind] = useState<ProcessKind | null>(null);
  const [selectedVideoId, setSelectedVideoId] = useState<number | null>(null);
  const [selectedPreset, setSelectedPreset] = useState<ResolutionPreset | null>(
    null,
  );

  // 필터: localFilters (입력 중), filters (적용된 값)
  const [localFilters, setLocalFilters] =
    useState<VideoFilterValues>(DEFAULT_FILTERS);
  const [filters, setFilters] = useState<VideoFilterValues>(DEFAULT_FILTERS);
  const [page, setPage] = useState(0);

  // 검수 완료(승인) 영상만 — V1.x SFR-07 가드
  // - dataSttsCd=COMPLETED: 배치 파이프라인 완료 (LS_DATA_RAW.DATA_STTS_CD)
  // - reviewStatusCd=APPROVED: 검수 승인 완료 (LS_RAW_DATA_STATUS.DATA_STTS_CD)
  const { data: videosPage, isLoading: videosLoading } = useVideos({
    page,
    size: PAGE_SIZE,
    dataSttsCd: 'COMPLETED',
    reviewStatusCd: 'APPROVED',
  });
  const pageContent = videosPage?.content ?? [];
  // 안전 가드: BE 응답에 배치 미완료 영상이 섞여도 화면 노출은 COMPLETED 만 허용 (SFR-07).
  const approvedPage = useMemo(
    () => pageContent.filter((v) => v.status === 'COMPLETED'),
    [pageContent],
  );

  // 이벤트 옵션 — 현재 페이지 승인 영상의 eventName(한글 표시명)에서 unique 수집.
  // 하드코딩 코드 목록을 폐지하고 실제 데이터 기반으로 노출 → 필터(eventName 비교)와 정합.
  const eventTypeOptions = useMemo(
    () =>
      Array.from(
        new Set(approvedPage.map((v) => v.eventName).filter((n): n is string => !!n)),
      ),
    [approvedPage],
  );

  // 클라이언트 필터 (현재 페이지 한정 — BE 검색 강화는 후속 PR)
  const pagedVideos = useMemo(() => {
    let result = approvedPage;
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
  }, [approvedPage, filters]);

  // BE 페이지 메타데이터
  const totalElements = videosPage?.totalElements ?? 0;
  const totalPages = Math.max(1, videosPage?.totalPages ?? 1);
  const currentPage = videosPage?.number ?? page;

  // 필터 적용 시 페이지 리셋
  useEffect(() => {
    setPage(0);
  }, [filters]);

  const { data, isLoading, error } = useAugmentJobs({ page: 0, size: 6 });
  const { mutate, isPending } = useRequestAugment({
    onSuccess: (resp) => {
      pushToast({ variant: 'success', message: '증강 요청 등록됨' });
      setSelectedKind(null);
      setSelectedVideoId(null);
      setSelectedPreset(null);
      if (resp?.jobId) {
        navigate(`/augment/result/${resp.jobId}`);
      }
    },
    onError: () => {
      pushToast({ variant: 'error', message: '증강 요청 실패' });
    },
  });

  // 해상도 변환(SFR-06-03) — 저작도구 직접 수행. rawSn 은 mutate 시점에 canSubmit 가드로 보장.
  // selectedVideoId 가 null 이면 0 을 넘기되 canSubmit 가 false 라 실제 호출은 차단된다.
  const resolutionExport = useResolutionExport(selectedVideoId ?? 0);
  const {
    data: resolutionResult,
    error: resolutionError,
    reset: resetResolution,
  } = resolutionExport;

  const isResolution = selectedKind === 'RESOLUTION';
  const isPendingAny = isPending || resolutionExport.isPending;

  // radiogroup 로빙 tabindex/화살표 탐색용 카드 ref (a11y WCAG 4.1.2).
  const kindCardRefs = useRef<(HTMLButtonElement | null)[]>([]);

  // 종류 변경: 타겟 해상도(preset)·해상도 결과 상태 초기화 (AC4).
  const handleSelectKind = (kind: ProcessKind) => {
    setSelectedKind(kind);
    setSelectedPreset(null);
    resetResolution();
  };

  // 화살표 키로 카드 간 이동 + 선택 + 포커스 이동 (로빙 tabindex 패턴).
  const handleKindKeyDown = (
    e: React.KeyboardEvent<HTMLButtonElement>,
    index: number,
  ) => {
    let nextIndex: number | null = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      nextIndex = (index + 1) % PROCESS_KINDS.length;
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      nextIndex = (index - 1 + PROCESS_KINDS.length) % PROCESS_KINDS.length;
    }
    if (nextIndex === null) return;
    e.preventDefault();
    handleSelectKind(PROCESS_KINDS[nextIndex]);
    kindCardRefs.current[nextIndex]?.focus();
  };

  // 미선택 상태면 첫 카드가 tab 진입점(0), 선택 상태면 선택된 카드만 0.
  const kindTabIndex = (kind: ProcessKind, index: number) =>
    selectedKind === null ? (index === 0 ? 0 : -1) : selectedKind === kind ? 0 : -1;

  const selectVideo = (id: number) => {
    setSelectedVideoId((prev) => (prev === id ? null : id));
  };

  const handleApplyFilters = (e: React.FormEvent) => {
    e.preventDefault();
    setFilters(localFilters);
  };

  const handleResetFilters = () => {
    setLocalFilters(DEFAULT_FILTERS);
    setFilters(DEFAULT_FILTERS);
  };

  // 제출 가능 조건 — 종류·영상 선택 필수, 해상도 종류면 preset 필수.
  const canSubmit =
    selectedKind !== null &&
    selectedVideoId !== null &&
    (!isResolution || selectedPreset !== null) &&
    !isPendingAny;

  // 실행 분기 (R4): 증강 3종은 위탁 잡 요청(/augments/request),
  // 해상도 변경은 저작도구 직접 수행(/videos/{rawSn}/resolution).
  // kind/preset 은 allowlist 상수로만 좁혀(isAugmentKind/RESOLUTION_PRESETS) 임의 분기 차단.
  const handleSubmit = () => {
    if (!canSubmit || selectedKind === null || selectedVideoId === null) return;
    if (isAugmentKind(selectedKind)) {
      // 증강: 성공 시 결과화면 네비게이션(useRequestAugment onSuccess).
      mutate({
        videoIds: [selectedVideoId],
        types: [selectedKind],
      });
    } else if (selectedPreset !== null) {
      // 해상도: 성공 시 결과 카드 inline 표시(아래 resolutionResult).
      resolutionExport.mutate(selectedPreset);
    }
  };

  const resolutionErrorMessage =
    resolutionError instanceof ApiError
      ? resolutionError.userMessage
      : resolutionError
        ? '해상도 변환에 실패했습니다.'
        : null;

  return (
    <section
      className="flex flex-col gap-6 pb-28"
      data-testid="augment-request-page"
    >
      <PageHeader
        title="데이터 증강 요청"
        description="검수 완료(승인) 영상 1건에 처리 종류(겨울/야간/우천 증강 또는 해상도 변경) 하나를 선택해 요청합니다."
      />

      {/* SFR-07 안내 */}
      <div className="flex items-start gap-2 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2.5">
        <Info size={14} className="mt-0.5 shrink-0 text-blue-600" aria-hidden />
        <p className="text-xs text-blue-700">
          처리 요청은 검수 완료(승인)된 영상만 가능합니다. 미승인 영상은 목록에 표시되지 않습니다.
        </p>
      </div>

      {/* Step 1: 처리 종류 선택 (단일 선택 카드 4개) */}
      <section className="space-y-4" data-testid="process-kind-step">
        <div className="flex items-center gap-2">
          <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary-600 text-xs font-bold text-white">
            1
          </span>
          <h2 className="text-base font-semibold text-gray-800">처리 종류 선택</h2>
          {selectedKind && (
            <span className="inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700">
              {PROCESS_KIND_LABEL[selectedKind]}
            </span>
          )}
        </div>

        <div
          role="radiogroup"
          aria-label="처리 종류"
          className="grid grid-cols-2 gap-4 md:grid-cols-4"
          data-testid="process-kind-list"
        >
          {PROCESS_KINDS.map((kind, index) => (
            <ProcessKindCard
              key={kind}
              ref={(el) => {
                kindCardRefs.current[index] = el;
              }}
              kind={kind}
              selected={selectedKind === kind}
              tabIndex={kindTabIndex(kind, index)}
              onSelect={() => handleSelectKind(kind)}
              onKeyDown={(e) => handleKindKeyDown(e, index)}
            />
          ))}
        </div>

        {selectedKind === null && (
          <p className="flex items-center gap-1.5 text-xs text-amber-600">
            <AlertCircle size={13} aria-hidden />
            처리 종류를 하나 선택하세요.
          </p>
        )}

        {/* 해상도 변경 종류 선택 시에만 타겟 해상도 UI 노출 (AC3) */}
        {isResolution && (
          <div className="space-y-2" data-testid="target-resolution-block">
            <p className="text-xs font-medium text-gray-600">타겟 해상도</p>
            <TargetResolutionSelect
              value={selectedPreset}
              onChange={(p) => {
                setSelectedPreset(p);
                resetResolution();
              }}
              disabled={resolutionExport.isPending}
            />

            {/* 해상도 변환 실행 결과 (AC5) */}
            {resolutionErrorMessage && (
              <p role="alert" className="text-sm text-red-600">
                {resolutionErrorMessage}
              </p>
            )}
            {resolutionResult && (
              <div
                role="status"
                data-testid="resolution-export-result"
                className="rounded-md border border-green-200 bg-green-50 px-3 py-2 text-sm text-green-800"
              >
                변환 완료 — {resolutionResult.srcW}×{resolutionResult.srcH} →{' '}
                {resolutionResult.targetW}×{resolutionResult.targetH}, 프레임{' '}
                {resolutionResult.frameCount}장 (export #{resolutionResult.exportSn})
              </div>
            )}
          </div>
        )}
      </section>

      {/* Step 2: 대상 영상 선택 (단일 선택) */}
      <section className="space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary-600 text-xs font-bold text-white">
            2
          </span>
          <h2 className="text-base font-semibold text-gray-800">대상 영상 선택</h2>
          <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
            검수 완료 {totalElements}건
          </span>
          {selectedVideoId !== null && (
            <span className="inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700">
              #{selectedVideoId} 선택
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

        {/* 영상 테이블 (단일 선택 라디오) */}
        {videosLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} height={32} />
            ))}
          </div>
        ) : pagedVideos.length === 0 ? (
          <EmptyState
            message={
              totalElements === 0
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
                  <th className="w-10 px-3 py-2" />
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
                  const checked = selectedVideoId === v.id;
                  return (
                    <tr
                      key={v.id}
                      className="cursor-pointer border-b border-gray-100 hover:bg-gray-50"
                      onClick={() => selectVideo(v.id)}
                    >
                      <td className="px-3 py-2">
                        <input
                          type="radio"
                          name="augment-target-video"
                          aria-label={`${v.cctvName} 선택`}
                          checked={checked}
                          onChange={() => selectVideo(v.id)}
                          onClick={(e) => e.stopPropagation()}
                          className="h-4 w-4 border-gray-300 text-primary-600 focus:ring-primary-500"
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
                        {v.reviewCompletedAt
                          ? new Date(v.reviewCompletedAt).toLocaleString(
                              'ko-KR',
                              {
                                year: 'numeric',
                                month: '2-digit',
                                day: '2-digit',
                                hour: '2-digit',
                                minute: '2-digit',
                              },
                            )
                          : '-'}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {totalPages > 1 && (
              <div className="flex items-center justify-between bg-gray-50 px-3 py-2 text-xs text-gray-500">
                <span>
                  전체 {totalElements}건 ({currentPage + 1}/{totalPages}{' '}
                  페이지)
                </span>
                <div className="flex gap-1">
                  <button
                    type="button"
                    onClick={() => setPage(Math.max(0, currentPage - 1))}
                    disabled={currentPage === 0}
                    className="rounded px-2 py-1 hover:bg-gray-200 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    이전
                  </button>
                  <button
                    type="button"
                    onClick={() => setPage(currentPage + 1)}
                    disabled={currentPage >= totalPages - 1}
                    className="rounded px-2 py-1 hover:bg-gray-200 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    다음
                  </button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* 선택 액션 배지 (단일) */}
        {selectedVideoId !== null && (
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-primary-200 bg-primary-50 px-4 py-3">
            <div className="flex items-center gap-3">
              <span className="inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700">
                영상 #{selectedVideoId} 선택됨
              </span>
              <button
                type="button"
                onClick={() => setSelectedVideoId(null)}
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
      <section aria-label="최근 요청 이력" className="flex flex-col gap-3">
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
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
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
              className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3"
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
              {selectedKind ? PROCESS_KIND_LABEL[selectedKind] : '종류 미선택'}
            </span>{' '}
            ×{' '}
            <span className="font-semibold text-primary-600">
              {selectedVideoId !== null ? `영상 #${selectedVideoId}` : '영상 미선택'}
            </span>
          </div>
          <div className="flex items-center gap-3">
            <Button
              variant="secondary"
              size="md"
              onClick={() => navigate(-1)}
              disabled={isPendingAny}
            >
              취소
            </Button>
            <Button
              data-testid="augment-submit"
              variant="primary"
              size="md"
              disabled={!canSubmit}
              loading={isPendingAny}
              onClick={handleSubmit}
            >
              <Wand2 size={14} aria-hidden />
              처리 요청
            </Button>
          </div>
        </div>
      </div>
    </section>
  );
}
