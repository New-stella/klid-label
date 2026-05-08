import { useState, useEffect, useMemo, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Wand2,
  AlertCircle,
  History,
  Info,
  Search,
  RotateCcw,
  X,
} from 'lucide-react';
import { useFetch, useMutation } from '../../api/queries';
import { api } from '../../api/client';
import type { Page, VideoDto, AugmentJob, AugmentDecision } from '../../api/types';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { Skeleton } from '../../components/ui/Skeleton';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { Table } from '../../components/ui/Table';
import { Pagination } from '../../components/ui/Pagination';
import { useToast } from '../../components/common/Toast';
import { AugmentTypeCard } from '../../components/augment/AugmentTypeCard';
import { EventTypeBadge } from '../../components/batch/EventTypeBadge';
import { formatDate } from '../../utils/format';
import type { ColumnDef } from '../../components/ui/Table';

type AugmentType = 'WINTER' | 'NIGHT' | 'RAIN' | 'RESOLUTION';

// 상태 매핑 (AugmentResult.tsx와 동일)
const STATUS_LABEL: Record<AugmentJob['status'], string> = {
  PENDING: '대기',
  PROCESSING: '처리 중',
  COMPLETED: '완료',
  FAILED: '실패',
};

const STATUS_TONE: Record<
  AugmentJob['status'],
  'neutral' | 'info' | 'success' | 'danger'
> = {
  PENDING: 'neutral',
  PROCESSING: 'info',
  COMPLETED: 'success',
  FAILED: 'danger',
};

const TYPE_CHIP: Record<AugmentType, string> = {
  WINTER: '❄️ 겨울',
  NIGHT: '🌙 야간',
  RAIN: '🌧 비',
  RESOLUTION: '📐 해상도',
};

// SFR-07 — 활용 결정 배지 라벨/톤
const DECISION_LABEL: Record<AugmentDecision, string> = {
  PENDING: '결정 대기',
  ACCEPTED: '활용',
  REJECTED: '거부',
};

const DECISION_TONE: Record<AugmentDecision, 'warning' | 'success' | 'danger'> = {
  PENDING: 'warning',
  ACCEPTED: 'success',
  REJECTED: 'danger',
};

const ALL_TYPES: AugmentType[] = ['WINTER', 'NIGHT', 'RAIN', 'RESOLUTION'];

const PAGE_SIZE = 100;

interface AugmentRequestBody {
  videoIds: string[];
  types: AugmentType[];
}

interface VideoFilterValues {
  q: string;
  eventType: string;
}

const DEFAULT_FILTERS: VideoFilterValues = {
  q: '',
  eventType: '',
};

function searchParamsToFilters(sp: URLSearchParams): VideoFilterValues {
  return {
    q: sp.get('q') ?? '',
    eventType: sp.get('eventType') ?? '',
  };
}

function filtersToSearchParams(f: VideoFilterValues): Record<string, string> {
  const out: Record<string, string> = {};
  if (f.q) out['q'] = f.q;
  if (f.eventType) out['eventType'] = f.eventType;
  return out;
}

export function AugmentRequest() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [searchParams, setSearchParams] = useSearchParams();

  // Step state
  const [selectedTypes, setSelectedTypes] = useState<Set<AugmentType>>(new Set());
  const [selectedVideoIds, setSelectedVideoIds] = useState<Set<string>>(new Set());

  // Filter / paging state
  const [filters, setFilters] = useState<VideoFilterValues>(() =>
    searchParamsToFilters(searchParams),
  );
  const [localFilters, setLocalFilters] = useState<VideoFilterValues>(filters);
  const [page, setPage] = useState(0);

  // 검수 완료 영상만 받기 위해 size를 충분히 크게 — FE에서 검색·필터·페이징 처리
  const { data, isLoading } = useFetch<Page<VideoDto>>('/videos', {
    status: 'COMPLETED',
    size: 999,
  });

  const { data: jobsPage, isLoading: jobsLoading, refetch: refetchJobs } = useFetch<Page<AugmentJob>>(
    '/augment',
    { page: 0, size: 6 },
  );

  const { mutate, isLoading: isSubmitting } = useMutation<AugmentRequestBody, AugmentJob>(
    (body) => api.post<AugmentJob>('/augment/request', body),
  );

  // PENDING/PROCESSING 잡이 있으면 5초마다 자동 갱신
  const hasPendingJobs = jobsPage?.content.some(
    (j) => j.status === 'PENDING' || j.status === 'PROCESSING',
  );
  useEffect(() => {
    if (!hasPendingJobs) return;
    const timer = setInterval(() => refetchJobs(), 5000);
    return () => clearInterval(timer);
  }, [hasPendingJobs, refetchJobs]);

  const allVideos = data?.content ?? [];

  // SFR-07 — 증강 요청은 검수 완료(승인)된 영상만 가능. taskStatus === 'COMPLETED'을 승인 판정으로 사용.
  // /videos 응답에는 batchStatus===COMPLETED 또는 taskStatus===COMPLETED가 섞여 있으므로 client-side로 한번 더 필터.
  const approvedVideos = useMemo(
    () => allVideos.filter((v) => v.taskStatus === 'COMPLETED'),
    [allVideos],
  );

  // 이벤트 유형 옵션 (검수 완료 영상에서 unique, 정렬)
  const eventTypeOptions = useMemo(() => {
    const set = new Set<string>();
    approvedVideos.forEach((v) => {
      if (v.eventType) set.add(v.eventType);
    });
    return Array.from(set).sort();
  }, [approvedVideos]);

  // 검색·이벤트 필터 적용
  const filteredVideos = useMemo(() => {
    let result = approvedVideos;
    if (filters.q) {
      const q = filters.q.toLowerCase();
      result = result.filter(
        (v) =>
          v.cctvName.toLowerCase().includes(q) ||
          v.id.toLowerCase().includes(q) ||
          v.eventType.toLowerCase().includes(q),
      );
    }
    if (filters.eventType) {
      result = result.filter((v) => v.eventType === filters.eventType);
    }
    return result;
  }, [approvedVideos, filters]);

  // 페이징
  const totalPages = Math.max(1, Math.ceil(filteredVideos.length / PAGE_SIZE));
  const pagedVideos = useMemo(
    () => filteredVideos.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE),
    [filteredVideos, page],
  );

  // 필터·검색 변경 시 URL 동기화 + 페이지 리셋
  useEffect(() => {
    const params = filtersToSearchParams(filters);
    setSearchParams(params, { replace: true });
    setPage(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters]);

  // 필터·검색 변경 시 보이지 않는 항목은 선택에서 자동 해제
  useEffect(() => {
    setSelectedVideoIds((prev) => {
      const visibleIds = new Set(filteredVideos.map((v) => v.id));
      const next = new Set<string>();
      for (const id of prev) if (visibleIds.has(id)) next.add(id);
      return next.size === prev.size ? prev : next;
    });
  }, [filteredVideos]);

  const handleApplyFilters = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      setFilters(localFilters);
    },
    [localFilters],
  );

  const handleResetFilters = useCallback(() => {
    setLocalFilters(DEFAULT_FILTERS);
    setFilters(DEFAULT_FILTERS);
  }, []);

  const toggleType = (type: AugmentType) => {
    setSelectedTypes((prev) => {
      const next = new Set(prev);
      if (next.has(type)) {
        next.delete(type);
      } else {
        next.add(type);
      }
      return next;
    });
  };

  const toggleRow = useCallback((videoId: string) => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (next.has(videoId)) next.delete(videoId);
      else next.add(videoId);
      return next;
    });
  }, []);

  // 현재 페이지의 영상ID 집합 (헤더 전체 선택)
  const pagedVideoIds = useMemo(() => pagedVideos.map((v) => v.id), [pagedVideos]);
  const allPagedSelected =
    pagedVideoIds.length > 0 && pagedVideoIds.every((vid) => selectedVideoIds.has(vid));
  const somePagedSelected = pagedVideoIds.some((vid) => selectedVideoIds.has(vid));

  const toggleAllPaged = useCallback(() => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (allPagedSelected) {
        pagedVideoIds.forEach((vid) => next.delete(vid));
      } else {
        pagedVideoIds.forEach((vid) => next.add(vid));
      }
      return next;
    });
  }, [allPagedSelected, pagedVideoIds]);

  const totalEstimated = selectedTypes.size * selectedVideoIds.size;
  const canSubmit = selectedTypes.size > 0 && selectedVideoIds.size > 0;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    try {
      const job = await mutate({
        videoIds: Array.from(selectedVideoIds),
        types: Array.from(selectedTypes),
      });
      showToast('증강 요청이 등록되었습니다.', 'success');
      refetchJobs();
      navigate(`/augment/result/${job.id}`);
    } catch {
      showToast('증강 요청 중 오류가 발생했습니다.', 'error');
    }
  };

  // ---------------------------------------------------------------------------
  // Table columns
  // ---------------------------------------------------------------------------
  const columns: ColumnDef<VideoDto>[] = [
    {
      key: '_select',
      header: '',
      width: '40px',
      render: (row) => (
        <div onClick={(e) => e.stopPropagation()} className="flex items-center">
          <input
            type="checkbox"
            aria-label={`${row.cctvName} 선택`}
            checked={selectedVideoIds.has(row.id)}
            onChange={() => toggleRow(row.id)}
            className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
          />
        </div>
      ),
    },
    {
      key: 'cctvName',
      header: '영상명 / CCTV명',
      render: (row) => (
        <div className="min-w-[160px]">
          <p className="font-medium text-gray-800 text-sm truncate max-w-[260px]">
            {row.cctvName}
          </p>
          <p className="text-xs text-gray-400">{row.id}</p>
        </div>
      ),
    },
    {
      key: 'eventType',
      header: '이벤트',
      render: (row) =>
        row.eventType ? (
          <EventTypeBadge eventType={row.eventType} />
        ) : (
          <span className="text-xs text-gray-400">-</span>
        ),
    },
    {
      key: 'recordedAt',
      header: '녹화일',
      render: (row) => (
        <span className="text-xs text-gray-500">
          {formatDate(row.recordedAt, 'YYYY-MM-DD')}
        </span>
      ),
    },
    {
      key: 'updatedAt',
      header: '검수 완료 일시',
      render: (row) => (
        <span className="text-xs text-gray-500">
          {formatDate(row.updatedAt, 'YYYY-MM-DD HH:mm')}
        </span>
      ),
    },
  ];

  const selectedCount = selectedVideoIds.size;

  return (
    <div className="p-6 pb-28 space-y-8">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-purple-50">
          <Wand2 size={20} className="text-purple-600" />
        </div>
        <h1 className="text-xl font-bold text-gray-900">데이터 증강 요청</h1>
        <span className="text-xs text-gray-500">
          증강 요청은 검수 완료(승인)된 영상만 가능합니다
        </span>
      </div>

      {/* Step 1: 증강 유형 선택 */}
      <section className="space-y-4">
        <div className="flex items-center gap-2">
          <span className="flex items-center justify-center w-7 h-7 rounded-full bg-primary-600 text-white text-xs font-bold shrink-0">
            1
          </span>
          <h2 className="text-base font-semibold text-gray-800">증강 유형 선택</h2>
          {selectedTypes.size > 0 && (
            <Badge tone="info" size="sm">
              {selectedTypes.size}종 선택
            </Badge>
          )}
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {ALL_TYPES.map((type) => (
            <AugmentTypeCard
              key={type}
              type={type}
              selected={selectedTypes.has(type)}
              onToggle={() => toggleType(type)}
            />
          ))}
        </div>

        {selectedTypes.size === 0 && (
          <p className="flex items-center gap-1.5 text-xs text-amber-600">
            <AlertCircle size={13} />
            증강 유형을 하나 이상 선택하세요.
          </p>
        )}
      </section>

      {/* Step 2: 대상 영상 선택 */}
      <section className="space-y-4">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="flex items-center justify-center w-7 h-7 rounded-full bg-primary-600 text-white text-xs font-bold shrink-0">
            2
          </span>
          <h2 className="text-base font-semibold text-gray-800">대상 영상 선택</h2>
          <Badge tone="neutral" size="sm">
            검수 완료 {approvedVideos.length}건
          </Badge>
          {selectedCount > 0 && (
            <Badge tone="success" size="sm">
              {selectedCount}건 선택
            </Badge>
          )}
        </div>

        {/* SFR-07 안내 — 검수 완료 영상만 증강 요청 가능 */}
        <p className="flex items-center gap-1.5 text-xs text-gray-500">
          <Info size={13} className="shrink-0" />
          증강 요청은 검수 완료(승인)된 영상만 가능합니다. 미승인 영상은 목록에 표시되지
          않습니다.
        </p>

        {/* 검색·필터 폼 */}
        <form
          onSubmit={handleApplyFilters}
          className="bg-white border border-gray-200 rounded-lg px-4 py-3 flex flex-wrap items-end gap-3 shadow-sm"
        >
          <div className="flex flex-col gap-1 min-w-[200px] flex-1">
            <label className="text-xs font-medium text-gray-500">영상명 / CCTV명</label>
            <div className="relative">
              <Search
                size={14}
                className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400"
              />
              <input
                type="text"
                value={localFilters.q}
                onChange={(e) =>
                  setLocalFilters((p) => ({ ...p, q: e.target.value }))
                }
                placeholder="검색어 입력"
                className="w-full pl-8 pr-3 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
              />
            </div>
          </div>

          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-gray-500">이벤트</label>
            <select
              value={localFilters.eventType}
              onChange={(e) =>
                setLocalFilters((p) => ({ ...p, eventType: e.target.value }))
              }
              className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              <option value="">전체</option>
              {eventTypeOptions.map((et) => (
                <option key={et} value={et}>
                  {et}
                </option>
              ))}
            </select>
          </div>

          <div className="flex gap-2 items-end">
            <Button type="submit" variant="primary" size="sm" leftIcon={Search}>
              조회
            </Button>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              leftIcon={RotateCcw}
              onClick={handleResetFilters}
            >
              초기화
            </Button>
          </div>
        </form>

        {/* 선택 액션바 — 1건 이상 선택 시 노출 */}
        {selectedCount >= 1 && (
          <div className="bg-primary-50 border border-primary-200 rounded-lg px-4 py-3 flex items-center justify-between flex-wrap gap-3">
            <div className="flex items-center gap-3">
              <Badge tone="info" size="sm">
                {selectedCount}개 영상 선택됨
              </Badge>
              <button
                type="button"
                onClick={() => setSelectedVideoIds(new Set())}
                className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-700 underline"
              >
                <X size={12} />
                선택 해제
              </button>
            </div>
            <Button
              variant="primary"
              size="sm"
              leftIcon={Wand2}
              loading={isSubmitting}
              disabled={!canSubmit}
              onClick={() => {
                void handleSubmit();
              }}
            >
              {selectedCount}개 영상 증강 요청
            </Button>
          </div>
        )}

        {/* Table */}
        {isLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 6 }, (_, i) => (
              <Skeleton key={i} height="3rem" />
            ))}
          </div>
        ) : (
          <div className="bg-white border border-gray-200 rounded-lg shadow-sm overflow-hidden">
            <div className="flex items-center px-4 py-2 border-b border-gray-100 bg-gray-50 gap-3">
              {pagedVideoIds.length > 0 && (
                <label className="flex items-center gap-2 text-xs text-gray-600 cursor-pointer">
                  <input
                    type="checkbox"
                    aria-label="현재 페이지 전체 선택"
                    checked={allPagedSelected}
                    ref={(el) => {
                      if (el) el.indeterminate = !allPagedSelected && somePagedSelected;
                    }}
                    onChange={toggleAllPaged}
                    className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                  />
                  현재 페이지 전체 선택
                </label>
              )}
              <span className="text-xs text-gray-500 ml-auto">
                전체 {filteredVideos.length}건
                {totalPages > 1 ? ` (${page + 1}/${totalPages} 페이지)` : ''}
              </span>
            </div>
            <Table<VideoDto>
              columns={columns}
              rows={pagedVideos}
              rowKey={(r) => r.id}
              onRowClick={(row) => toggleRow(row.id)}
              emptyMessage={
                approvedVideos.length === 0
                  ? '검수 완료된 영상이 없습니다.'
                  : '검색 조건에 맞는 영상이 없습니다.'
              }
            />
          </div>
        )}

        {/* Pagination */}
        <Pagination page={page} totalPages={totalPages} onChange={(p) => setPage(p)} />

        {selectedCount === 0 && !isLoading && filteredVideos.length > 0 && (
          <p className="flex items-center gap-1.5 text-xs text-amber-600">
            <AlertCircle size={13} />
            대상 영상을 하나 이상 선택하세요.
          </p>
        )}
      </section>

      {/* 최근 요청 이력 */}
      <section className="space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <History size={18} className="text-gray-500" />
            <h2 className="text-base font-semibold text-gray-800">최근 요청 이력</h2>
          </div>
          {jobsPage && (
            <Badge tone="neutral" size="sm">
              {jobsPage.totalElements}건
            </Badge>
          )}
        </div>

        {jobsLoading && !jobsPage ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {Array.from({ length: 6 }, (_, i) => (
              <Skeleton key={i} height="9rem" />
            ))}
          </div>
        ) : !jobsPage || jobsPage.content.length === 0 ? (
          <div className="bg-gray-50 rounded-lg p-8 text-center text-sm text-gray-500">
            아직 증강 요청 이력이 없습니다.
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {jobsPage.content.map((job) => {
              const progressTone =
                job.status === 'FAILED'
                  ? 'danger'
                  : job.status === 'COMPLETED'
                    ? 'success'
                    : 'primary';

              return (
                <button
                  key={job.id}
                  type="button"
                  onClick={() => navigate(`/augment/result/${job.id}`)}
                  className={[
                    'text-left bg-white border border-gray-200 rounded-lg p-4 space-y-3',
                    'hover:border-primary-400 hover:shadow-sm transition-all cursor-pointer',
                    'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-1',
                  ].join(' ')}
                >
                  {/* 상단: jobId + 상태 배지 + (COMPLETED만) 활용 결정 배지 */}
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-xs text-gray-400 truncate">{job.id}</span>
                    <div className="flex items-center gap-1.5 shrink-0">
                      {job.status === 'COMPLETED' && (
                        <Badge tone={DECISION_TONE[job.decision ?? 'PENDING']} size="sm">
                          {DECISION_LABEL[job.decision ?? 'PENDING']}
                        </Badge>
                      )}
                      <Badge tone={STATUS_TONE[job.status]} size="sm">
                        {STATUS_LABEL[job.status]}
                      </Badge>
                    </div>
                  </div>

                  {/* 증강 유형 칩들 */}
                  <div className="flex flex-wrap gap-1">
                    {job.types.map((t) => (
                      <span
                        key={t}
                        className="inline-flex items-center text-xs bg-purple-50 text-purple-700 px-2 py-0.5 rounded-full"
                      >
                        {TYPE_CHIP[t]}
                      </span>
                    ))}
                  </div>

                  {/* 영상 건수 + 생성 시각 */}
                  <div className="flex items-center justify-between text-xs text-gray-500">
                    <span>{job.videoIds.length}건 영상</span>
                    <span>
                      {new Date(job.createdAt).toLocaleString('ko-KR', {
                        month: '2-digit',
                        day: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </span>
                  </div>

                  {/* 진행률 */}
                  {job.status === 'FAILED' ? (
                    <p className="text-xs font-medium text-red-500">처리 실패</p>
                  ) : (
                    <div className="space-y-1">
                      <div className="flex items-center justify-between text-xs text-gray-400">
                        <span>진행률</span>
                        <span className="tabular-nums">
                          {job.status === 'COMPLETED' ? 100 : job.progress}%
                        </span>
                      </div>
                      <ProgressBar
                        value={job.status === 'COMPLETED' ? 100 : job.progress}
                        tone={progressTone}
                        size="sm"
                      />
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </section>

      {/* Bottom bar */}
      <div className="fixed bottom-0 left-0 right-0 z-20 bg-white border-t border-gray-200 px-6 py-4">
        <div className="max-w-6xl mx-auto flex items-center justify-between gap-4">
          <div className="text-sm text-gray-600">
            선택:{' '}
            <span className="font-semibold text-primary-600">
              {selectedTypes.size}종
            </span>{' '}
            ×{' '}
            <span className="font-semibold text-primary-600">{selectedCount}건</span>{' '}
            = 예상 <span className="font-bold text-gray-900">{totalEstimated}건</span>
          </div>
          <div className="flex items-center gap-3">
            <Button
              variant="secondary"
              size="md"
              onClick={() => navigate(-1)}
              disabled={isSubmitting}
            >
              취소
            </Button>
            <Button
              variant="primary"
              size="md"
              leftIcon={Wand2}
              loading={isSubmitting}
              disabled={!canSubmit}
              onClick={() => {
                void handleSubmit();
              }}
            >
              증강 요청
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

export default AugmentRequest;
