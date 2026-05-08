import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  CheckCircle2,
  Eye,
  FileJson,
  Info,
  RefreshCw,
  RotateCcw,
  Search,
  Send,
  Server,
  ShieldCheck,
  XCircle,
} from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { Pagination } from '@/components/common/Pagination';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { StatusBadge } from '@/components/common/StatusBadge';
import {
  useDatasets,
  usePrepareExport,
  useRecentExports,
} from '@/features/export/hooks/useExport';
import {
  ExportFormat,
  SUPPORTED_EXPORT_FORMATS,
  type ExportFormat as Format,
  type ExportPreview,
} from '@/features/export/types';
import { useVideos } from '@/features/video/hooks/useVideos';
import type { Video } from '@/features/video/types';
import { cn } from '@/lib/cn';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCR-EXPORT-001 학습데이터셋 내보내기 (`/export`).
 *
 * V1.x mock 정합 (mock/src/pages/export/ExportPage.tsx, 858 line):
 * - 4 포맷 라디오 (COCO/YOLO/CVAT/PASCAL_VOC) — BE 미지원 포맷은 비활성
 * - 영상 선택 가능 테이블 (체크박스 + 전체 선택) — 검수 완료(APPROVED)만
 * - 검색 + 이벤트 유형 필터 + 전송 상태 필터 (전체/미전송/이미 전송됨/전송 실패)
 * - 클라이언트 사이드 페이지네이션 (100/page)
 * - 강제 재전송 토글
 * - 미리보기 사이드 카드 (포맷 샘플 + 실행 버튼)
 * - 최근 요청 이력 카드
 *
 * 보안:
 * - REVIEWER 역할 (라우터 + BE @PreAuthorize)
 * - format 은 enum allowlist (라디오로 강제)
 * - BE 비식별 실패 영상은 서버에서 자동 제외 (FE 는 안내만)
 */

type ExportFilterValue = 'ALL' | 'NEVER' | 'EXPORTED' | 'FAILED';

const EXPORT_FILTER_OPTIONS: { value: ExportFilterValue; label: string }[] = [
  { value: 'ALL', label: '전체' },
  { value: 'NEVER', label: '미전송' },
  { value: 'EXPORTED', label: '이미 전송됨' },
  { value: 'FAILED', label: '전송 실패' },
];

const PAGE_SIZE = 100;

const FORMAT_META: Record<
  Format,
  { label: string; description: string; extension: string }
> = {
  COCO: {
    label: 'COCO',
    description: 'JSON 포맷 — 바운딩박스·세그멘테이션 모두 지원',
    extension: '.json',
  },
  YOLO: {
    label: 'YOLO',
    description: 'TXT 포맷 — 정규화 좌표, YOLO v5/v8 학습 표준',
    extension: '.txt',
  },
  CVAT: {
    label: 'CVAT',
    description: 'XML 포맷 — CVAT 호환 어노테이션, 폴리곤·트랙 포함',
    extension: '.xml',
  },
  PASCAL_VOC: {
    label: 'Pascal VOC',
    description: 'XML 포맷 — 이미지별 개별 어노테이션',
    extension: '.xml',
  },
};

const FORMAT_PREVIEW: Record<Format, string> = {
  COCO: `{
  "info": { "version": "1.0", "description": "KLID Export" },
  "categories": [
    { "id": 1, "name": "person" },
    { "id": 2, "name": "vehicle" }
  ],
  "images": [
    { "id": 1, "file_name": "frame_0001.jpg", "width": 1920, "height": 1080 }
  ],
  "annotations": [
    { "id": 1, "image_id": 1, "category_id": 1,
      "bbox": [120, 80, 60, 140], "area": 8400, "iscrowd": 0 }
  ]
}`,
  YOLO: `# frame_0001.jpg
# format: class_id cx cy w h (normalized)
0 0.3125 0.2963 0.0313 0.1296
1 0.7500 0.5556 0.1563 0.2963

# frame_0002.jpg
0 0.2083 0.3704 0.0417 0.1481`,
  CVAT: `<?xml version="1.0" encoding="utf-8"?>
<annotations>
  <version>1.1</version>
  <meta>
    <task><name>KLID Export</name></task>
  </meta>
  <image id="1" name="frame_0001.jpg" width="1920" height="1080">
    <box label="person" occluded="0"
         xtl="120" ytl="80" xbr="180" ybr="220" />
  </image>
</annotations>`,
  PASCAL_VOC: `<?xml version="1.0"?>
<annotation>
  <filename>frame_0001.jpg</filename>
  <size><width>1920</width><height>1080</height><depth>3</depth></size>
  <object>
    <name>person</name>
    <difficult>0</difficult>
    <bndbox>
      <xmin>120</xmin><ymin>80</ymin>
      <xmax>180</xmax><ymax>220</ymax>
    </bndbox>
  </object>
</annotation>`,
};

interface VideoFilterValues {
  q: string;
  eventType: string;
  exportFilter: ExportFilterValue;
}

const DEFAULT_FILTERS: VideoFilterValues = {
  q: '',
  eventType: '',
  exportFilter: 'ALL',
};

function isExportFilterValue(v: string): v is ExportFilterValue {
  return v === 'ALL' || v === 'NEVER' || v === 'EXPORTED' || v === 'FAILED';
}

function searchParamsToFilters(sp: URLSearchParams): VideoFilterValues {
  const ef = sp.get('exportFilter') ?? 'ALL';
  return {
    q: sp.get('q') ?? '',
    eventType: sp.get('eventType') ?? '',
    exportFilter: isExportFilterValue(ef) ? ef : 'ALL',
  };
}

function filtersToSearchParams(f: VideoFilterValues): Record<string, string> {
  const out: Record<string, string> = {};
  if (f.q) out['q'] = f.q;
  if (f.eventType) out['eventType'] = f.eventType;
  if (f.exportFilter !== 'ALL') out['exportFilter'] = f.exportFilter;
  return out;
}

/**
 * mock 의 exportStatus 는 우리 BE 에 없는 필드. videoId 가 selection 에 있으면 NEVER,
 * 추후 BE 가 video 단위 export 이력을 제공하면 매핑 추가.
 */
function deriveExportStatus(_video: Video): 'NEVER' | 'EXPORTED' | 'FAILED' {
  // 현재 BE 는 영상 단위 export 이력을 노출하지 않으므로 기본값 NEVER.
  return 'NEVER';
}

export function ExportPage() {
  const pushToast = useUiStore((s) => s.pushToast);
  const [searchParams, setSearchParams] = useSearchParams();

  const [format, setFormat] = useState<Format>(ExportFormat.COCO);
  const [page, setPage] = useState(0);
  const [selectedVideoIds, setSelectedVideoIds] = useState<Set<number>>(
    new Set(),
  );
  const [forceReexport, setForceReexport] = useState(false);
  const [datasetId, setDatasetId] = useState<number | null>(null);
  const [preview, setPreview] = useState<ExportPreview | null>(null);

  const [filters, setFilters] = useState<VideoFilterValues>(() =>
    searchParamsToFilters(searchParams),
  );
  const [localFilters, setLocalFilters] = useState<VideoFilterValues>(filters);

  // 데이터셋 목록 (라디오 그룹 — 첫 번째 자동 선택)
  const {
    data: datasets,
    isLoading: dsLoading,
    error: dsError,
  } = useDatasets();
  useEffect(() => {
    if (datasetId === null && datasets && datasets.length > 0) {
      setDatasetId(datasets[0].id);
    }
  }, [datasets, datasetId]);

  // 검수 완료(APPROVED) 영상 목록 — BE 페이징(0, 999)으로 충분히 받고 클라이언트에서 필터/페이징
  const { data: videoPage, isLoading: vidLoading } = useVideos({
    page: 0,
    size: 999,
    sort: 'capturedAt,desc',
  });

  const approvedVideos = useMemo<Video[]>(
    () =>
      (videoPage?.content ?? []).filter(
        (v) => v.status === 'APPROVED' || v.status === 'COMPLETED',
      ),
    [videoPage],
  );

  // 이벤트 유형 옵션 (검수 완료 영상에서 unique, 정렬)
  const eventTypeOptions = useMemo(() => {
    const set = new Set<string>();
    approvedVideos.forEach((v) => {
      if (v.eventTypeCd) set.add(v.eventTypeCd);
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
          String(v.id).toLowerCase().includes(q) ||
          (v.eventTypeCd ?? '').toLowerCase().includes(q),
      );
    }
    if (filters.eventType) {
      result = result.filter((v) => v.eventTypeCd === filters.eventType);
    }
    if (filters.exportFilter !== 'ALL') {
      result = result.filter(
        (v) => deriveExportStatus(v) === filters.exportFilter,
      );
    }
    return result;
  }, [approvedVideos, filters]);

  const totalPages = Math.max(1, Math.ceil(filteredVideos.length / PAGE_SIZE));
  const pagedVideos = useMemo(
    () => filteredVideos.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE),
    [filteredVideos, page],
  );

  // 필터 변경 시 URL 동기화 + 페이지 리셋
  useEffect(() => {
    const params = filtersToSearchParams(filters);
    setSearchParams(params, { replace: true });
    setPage(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters]);

  // 페이지 범위 보정
  useEffect(() => {
    if (page >= totalPages) setPage(0);
  }, [page, totalPages]);

  // EXPORTED 인데 forceReexport OFF 인 행은 disabled
  const isRowDisabled = useCallback(
    (video: Video): boolean =>
      !forceReexport && deriveExportStatus(video) === 'EXPORTED',
    [forceReexport],
  );

  const selectablePagedVideos = useMemo(
    () => pagedVideos.filter((v) => !isRowDisabled(v)),
    [pagedVideos, isRowDisabled],
  );

  const selectablePagedIds = useMemo(
    () => selectablePagedVideos.map((v) => v.id),
    [selectablePagedVideos],
  );

  const allPagedSelected =
    selectablePagedIds.length > 0 &&
    selectablePagedIds.every((id) => selectedVideoIds.has(id));
  const somePagedSelected = selectablePagedIds.some((id) =>
    selectedVideoIds.has(id),
  );

  // 필터 변경 시 보이지 않는 항목은 선택에서 자동 해제
  useEffect(() => {
    setSelectedVideoIds((prev) => {
      const visibleIds = new Set(filteredVideos.map((v) => v.id));
      const next = new Set<number>();
      for (const id of prev) if (visibleIds.has(id)) next.add(id);
      return next.size === prev.size ? prev : next;
    });
  }, [filteredVideos]);

  const toggleVideo = (video: Video) => {
    if (isRowDisabled(video)) return;
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (next.has(video.id)) {
        next.delete(video.id);
      } else {
        next.add(video.id);
      }
      return next;
    });
  };

  const toggleAllPaged = () => {
    if (selectablePagedIds.length === 0) return;
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (allPagedSelected) {
        for (const id of selectablePagedIds) next.delete(id);
      } else {
        for (const id of selectablePagedIds) next.add(id);
      }
      return next;
    });
  };

  const handleToggleForceReexport = (next: boolean) => {
    setForceReexport(next);
    if (!next) {
      setSelectedVideoIds((prev) => {
        const filtered = new Set<number>();
        for (const id of prev) {
          const v = approvedVideos.find((x) => x.id === id);
          if (v && deriveExportStatus(v) !== 'EXPORTED') filtered.add(id);
        }
        return filtered;
      });
    }
  };

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

  const { mutate, isPending } = usePrepareExport({
    onSuccess: (data) => {
      const res = data as unknown as { exportId: number; preview?: ExportPreview };
      if (res.preview) setPreview(res.preview);
      pushToast({ variant: 'success', message: '내보내기 준비 완료' });
      setSelectedVideoIds(new Set());
    },
    onError: () => {
      pushToast({ variant: 'error', message: '내보내기 준비 실패' });
    },
  });

  // 최근 요청 이력
  const { data: recentExports } = useRecentExports(5);

  const isFormatSupported = SUPPORTED_EXPORT_FORMATS.includes(format);
  const canSubmit =
    datasetId !== null && format && isFormatSupported && !isPending;

  const handleExecute = () => {
    if (!canSubmit || datasetId === null) return;
    if (!isFormatSupported) {
      pushToast({
        variant: 'info',
        message: `${FORMAT_META[format].label} 포맷은 현재 지원되지 않습니다 (COCO/YOLO 만 지원)`,
      });
      return;
    }
    mutate({
      datasetId,
      format,
      videoIds: Array.from(selectedVideoIds),
    });
  };

  const handlePreview = () => {
    handleExecute();
  };

  const isFilterActive =
    filters.q !== '' ||
    filters.eventType !== '' ||
    filters.exportFilter !== 'ALL';

  return (
    <section className="flex flex-col gap-5" data-testid="export-page">
      <PageHeader
        title="학습데이터셋 내보내기"
        description="검수 완료된 학습데이터셋을 NAS 경로로 내보냅니다."
        actions={
          <div className="flex items-center justify-center rounded-lg bg-green-50 p-2">
            <Send className="h-5 w-5 text-green-600" aria-hidden />
          </div>
        }
      />

      <div className="flex items-start gap-2 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2.5">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-blue-600" aria-hidden />
        <p className="text-sub text-blue-700">
          내보내기는 검수 완료(승인)된 영상만 가능합니다. 비식별 처리 실패 영상은 BE 에서 자동
          제외됩니다.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          {/* Step 1: 데이터셋 선택 */}
          <section className="space-y-4 rounded-lg border border-gray-200 bg-white p-5">
            <div className="flex items-center gap-2">
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary-600 text-sub font-bold text-white">
                1
              </span>
              <h2 className="text-section-title text-primary">데이터셋 선택</h2>
            </div>
            {dsError && <ErrorState title="데이터셋 목록을 불러올 수 없습니다" />}
            {dsLoading && <Skeleton height={48} />}
            {datasets && datasets.length > 0 && (
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {datasets.map((d) => {
                  const selected = datasetId === d.id;
                  return (
                    <label
                      key={d.id}
                      className={cn(
                        'flex cursor-pointer items-start gap-2 rounded-lg border-2 p-3 transition-colors',
                        selected
                          ? 'border-primary-500 bg-primary-50'
                          : 'border-gray-200 bg-white hover:border-gray-300',
                      )}
                    >
                      <input
                        type="radio"
                        name="export-dataset"
                        value={String(d.id)}
                        checked={selected}
                        onChange={() => setDatasetId(d.id)}
                        className="mt-0.5 h-4 w-4 border-gray-300 text-primary-600 focus:ring-primary-500"
                      />
                      <div className="min-w-0">
                        <p className="text-body font-medium text-gray-800">
                          {d.name}
                        </p>
                        <p className="text-sub text-gray-500">
                          영상 {d.videoCount.toLocaleString('ko-KR')}건
                        </p>
                      </div>
                    </label>
                  );
                })}
              </div>
            )}
          </section>

          {/* Step 2: 형식 선택 (4 포맷) */}
          <section className="space-y-4 rounded-lg border border-gray-200 bg-white p-5">
            <div className="flex items-center gap-2">
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary-600 text-sub font-bold text-white">
                2
              </span>
              <h2 className="text-section-title text-primary">형식 선택</h2>
            </div>

            <div className="grid grid-cols-2 gap-3">
              {(Object.keys(FORMAT_META) as Format[]).map((f) => {
                const meta = FORMAT_META[f];
                const selected = format === f;
                const supported = SUPPORTED_EXPORT_FORMATS.includes(f);
                return (
                  <label
                    key={f}
                    className={cn(
                      'flex items-start gap-3 rounded-lg border-2 p-3 transition-colors',
                      supported
                        ? 'cursor-pointer'
                        : 'cursor-not-allowed opacity-60',
                      selected
                        ? 'border-primary-500 bg-primary-50'
                        : 'border-gray-200 bg-white hover:border-gray-300',
                    )}
                    title={
                      supported
                        ? undefined
                        : '현재 BE 미지원 — COCO/YOLO 만 가능'
                    }
                  >
                    <input
                      type="radio"
                      name="export-format"
                      value={f}
                      checked={selected}
                      onChange={() => setFormat(f)}
                      disabled={!supported}
                      className="mt-0.5 h-4 w-4 border-gray-300 text-primary-600 focus:ring-primary-500 disabled:cursor-not-allowed"
                    />
                    <div className="min-w-0">
                      <p className="text-body font-semibold text-gray-800">
                        {meta.label}
                        <span className="ml-1 text-sub font-normal text-gray-400">
                          {meta.extension}
                        </span>
                        {!supported && (
                          <span className="ml-2 inline-flex items-center rounded-full bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500">
                            지원 예정
                          </span>
                        )}
                      </p>
                      <p className="text-sub text-gray-500">{meta.description}</p>
                    </div>
                  </label>
                );
              })}
            </div>
          </section>

          {/* Step 3: 대상 영상 */}
          <section className="space-y-4 rounded-lg border border-gray-200 bg-white p-5">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex flex-wrap items-center gap-2">
                <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary-600 text-sub font-bold text-white">
                  3
                </span>
                <h2 className="text-section-title text-primary">대상 영상</h2>
                <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-sub font-medium text-gray-700">
                  선택 가능 {filteredVideos.length}건
                </span>
                {selectedVideoIds.size > 0 && (
                  <span className="inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-sub font-medium text-green-700">
                    {selectedVideoIds.size}건 선택
                  </span>
                )}
              </div>

              <label className="flex cursor-pointer items-center gap-2 text-sub text-gray-600">
                <input
                  type="checkbox"
                  checked={forceReexport}
                  onChange={(e) => handleToggleForceReexport(e.target.checked)}
                  className="h-3.5 w-3.5 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span>이미 내보낸 영상 포함 (강제 재전송)</span>
              </label>
            </div>

            <form
              onSubmit={handleApplyFilters}
              className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-gray-50 px-3 py-3"
            >
              <div className="flex min-w-[180px] flex-1 flex-col gap-1">
                <label
                  htmlFor="export-q-input"
                  className="text-sub font-medium text-gray-500"
                >
                  영상명 / CCTV / ID
                </label>
                <div className="relative">
                  <Search
                    size={13}
                    className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400"
                    aria-hidden
                  />
                  <input
                    id="export-q-input"
                    type="text"
                    value={localFilters.q}
                    onChange={(e) =>
                      setLocalFilters((p) => ({ ...p, q: e.target.value }))
                    }
                    placeholder="검색어 입력"
                    className="w-full rounded-md border border-gray-300 bg-white py-1.5 pl-7 pr-3 text-body focus:outline-none focus:ring-2 focus:ring-primary-500"
                  />
                </div>
              </div>

              <div className="flex flex-col gap-1">
                <label
                  htmlFor="export-event-filter"
                  className="text-sub font-medium text-gray-500"
                >
                  이벤트
                </label>
                <select
                  id="export-event-filter"
                  value={localFilters.eventType}
                  onChange={(e) =>
                    setLocalFilters((p) => ({ ...p, eventType: e.target.value }))
                  }
                  className="rounded-md border border-gray-300 bg-white px-2 py-1.5 text-body focus:outline-none focus:ring-2 focus:ring-primary-500"
                >
                  <option value="">전체</option>
                  {eventTypeOptions.map((et) => (
                    <option key={et} value={et}>
                      {et}
                    </option>
                  ))}
                </select>
              </div>

              <div className="flex flex-col gap-1">
                <label
                  htmlFor="export-status-filter"
                  className="text-sub font-medium text-gray-500"
                >
                  전송 상태
                </label>
                <select
                  id="export-status-filter"
                  value={localFilters.exportFilter}
                  onChange={(e) => {
                    const v = e.target.value;
                    if (isExportFilterValue(v)) {
                      setLocalFilters((p) => ({ ...p, exportFilter: v }));
                    }
                  }}
                  className="rounded-md border border-gray-300 bg-white px-2 py-1.5 text-body focus:outline-none focus:ring-2 focus:ring-primary-500"
                >
                  {EXPORT_FILTER_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </select>
              </div>

              <div className="flex items-end gap-2">
                <Button type="submit" variant="primary" size="sm">
                  <Search className="mr-1 h-3.5 w-3.5" aria-hidden /> 조회
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={handleResetFilters}
                >
                  <RotateCcw className="mr-1 h-3.5 w-3.5" aria-hidden /> 초기화
                </Button>
              </div>
            </form>

            {vidLoading ? (
              <div className="space-y-2">
                {Array.from({ length: 5 }).map((_, i) => (
                  <Skeleton key={i} height={40} />
                ))}
              </div>
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-body" data-testid="export-video-table">
                    <thead>
                      <tr className="border-b border-gray-200">
                        <th className="w-10 py-2 pr-3 text-left">
                          <input
                            type="checkbox"
                            checked={allPagedSelected}
                            ref={(el) => {
                              if (el) {
                                el.indeterminate =
                                  !allPagedSelected && somePagedSelected;
                              }
                            }}
                            onChange={toggleAllPaged}
                            disabled={selectablePagedIds.length === 0}
                            className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                            aria-label="현재 페이지 전체 선택"
                          />
                        </th>
                        <th className="py-2 text-left text-sub font-medium text-gray-600">
                          영상 / CCTV
                        </th>
                        <th className="py-2 text-left text-sub font-medium text-gray-600">
                          이벤트
                        </th>
                        <th className="py-2 text-left text-sub font-medium text-gray-600">
                          녹화일
                        </th>
                        <th className="py-2 text-left text-sub font-medium text-gray-600">
                          전송 상태
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {pagedVideos.map((video) => {
                        const checked = selectedVideoIds.has(video.id);
                        const disabled = isRowDisabled(video);
                        const exportStatus = deriveExportStatus(video);
                        return (
                          <tr
                            key={video.id}
                            className={cn(
                              'border-b border-gray-100',
                              disabled
                                ? 'cursor-not-allowed opacity-50'
                                : 'cursor-pointer hover:bg-gray-50',
                            )}
                            onClick={() => toggleVideo(video)}
                          >
                            <td className="py-2 pr-3">
                              <input
                                type="checkbox"
                                checked={checked}
                                disabled={disabled}
                                onChange={() => toggleVideo(video)}
                                onClick={(e) => e.stopPropagation()}
                                className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500 disabled:cursor-not-allowed"
                                aria-label={`영상 ${video.cctvName} 선택`}
                              />
                            </td>
                            <td className="max-w-[260px] py-2">
                              <p className="truncate text-body font-medium text-gray-800">
                                {video.cctvName}
                              </p>
                              <p className="truncate font-mono text-sub text-gray-400">
                                #{video.id}
                              </p>
                            </td>
                            <td className="py-2">
                              {video.eventTypeCd ? (
                                <span className="inline-flex items-center rounded-full bg-blue-50 px-2 py-0.5 text-sub font-medium text-blue-700">
                                  {video.eventName ?? video.eventTypeCd}
                                </span>
                              ) : (
                                <span className="text-sub text-gray-400">-</span>
                              )}
                            </td>
                            <td className="py-2 text-sub text-gray-500">
                              {video.capturedAt
                                ? video.capturedAt.slice(0, 10)
                                : '-'}
                            </td>
                            <td className="py-2">
                              {exportStatus === 'EXPORTED' ? (
                                <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-sub font-medium text-green-700">
                                  <CheckCircle2 size={11} aria-hidden /> 전송됨
                                </span>
                              ) : exportStatus === 'FAILED' ? (
                                <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-sub font-medium text-red-700">
                                  <XCircle size={11} aria-hidden /> 전송 실패
                                </span>
                              ) : (
                                <span className="text-sub text-gray-300">—</span>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                      {pagedVideos.length === 0 && (
                        <tr>
                          <td
                            colSpan={5}
                            className="py-10 text-center text-sub text-gray-400"
                          >
                            {approvedVideos.length === 0
                              ? '검수 완료된 영상이 없습니다.'
                              : isFilterActive
                                ? '검색 조건에 맞는 영상이 없습니다.'
                                : '표시할 영상이 없습니다.'}
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>

                {filteredVideos.length > PAGE_SIZE && (
                  <Pagination
                    page={page}
                    size={PAGE_SIZE}
                    totalElements={filteredVideos.length}
                    onPageChange={setPage}
                  />
                )}
              </>
            )}
          </section>
        </div>

        {/* Right column: 미리보기 + 실행 + 최근 이력 */}
        <div className="space-y-4">
          <div className="sticky top-6 space-y-3 rounded-lg border border-gray-200 bg-white p-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 text-body font-semibold text-gray-700">
                <Eye size={15} aria-hidden />
                <span>미리보기 — {FORMAT_META[format].label}</span>
              </div>
              <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-sub font-medium text-gray-700">
                {FORMAT_META[format].extension}
              </span>
            </div>

            <div className="flex items-center gap-1.5 text-sub text-gray-400">
              <FileJson size={13} aria-hidden />
              <span>샘플 출력 형식</span>
            </div>

            <pre className="max-h-80 overflow-y-auto overflow-x-auto rounded-lg bg-gray-50 p-3 text-sub leading-relaxed text-gray-700">
              {FORMAT_PREVIEW[format]}
            </pre>

            <Button
              variant="ghost"
              size="sm"
              onClick={handlePreview}
              disabled={!canSubmit}
              data-testid="export-preview-btn"
              className="w-full"
            >
              <RefreshCw className="mr-1 h-3.5 w-3.5" aria-hidden />
              미리보기 갱신
            </Button>

            <Button
              variant="primary"
              onClick={handleExecute}
              disabled={!canSubmit}
              loading={isPending}
              data-testid="export-execute-btn"
              className="w-full"
            >
              <Send className="mr-1 h-4 w-4" aria-hidden />
              포털 전송 실행
            </Button>

            <p className="text-center text-sub text-gray-400">
              선택된 영상 {selectedVideoIds.size}건
              {forceReexport && (
                <span className="ml-1 text-amber-600">· 강제 재전송</span>
              )}
            </p>

            <div className="flex items-start gap-2 rounded-lg bg-gray-50 px-3 py-2">
              <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary-500" aria-hidden />
              <p className="text-sub text-gray-500">
                비식별 처리 실패 영상은 BE 에서 자동 제외됩니다.
              </p>
            </div>

            {preview && (
              <section
                data-testid="export-preview"
                aria-label="미리보기 결과"
                className="space-y-1 rounded-lg border border-gray-200 bg-gray-50 p-3"
              >
                <h3 className="text-sub font-semibold text-gray-700">
                  미리보기 결과
                </h3>
                <dl className="grid grid-cols-3 gap-2 text-center">
                  <Field label="영상">
                    {preview.videoCount.toLocaleString('ko-KR')}
                  </Field>
                  <Field label="프레임">
                    {preview.frameCount.toLocaleString('ko-KR')}
                  </Field>
                  <Field label="라벨">
                    {preview.labelCount.toLocaleString('ko-KR')}
                  </Field>
                </dl>
              </section>
            )}
          </div>

          {/* 최근 요청 이력 */}
          <section
            aria-label="최근 요청 이력"
            data-testid="export-recent-history"
            className="space-y-2 rounded-lg border border-gray-200 bg-white p-5"
          >
            <div className="flex items-center gap-2 text-body font-semibold text-gray-700">
              <Server size={15} aria-hidden />
              <span>최근 요청 이력</span>
            </div>
            {!recentExports || recentExports.length === 0 ? (
              <p className="py-2 text-center text-sub text-gray-400">
                최근 내보내기 이력이 없습니다.
              </p>
            ) : (
              <ul className="divide-y divide-gray-100">
                {recentExports.map((ex) => (
                  <li
                    key={ex.exportId}
                    className="flex items-center justify-between py-2 text-sub"
                  >
                    <div className="min-w-0">
                      <p className="font-mono text-gray-700">#{ex.exportId}</p>
                      <p className="truncate text-gray-400">
                        {ex.format ?? '-'} ·{' '}
                        {ex.registeredAt
                          ? ex.registeredAt.slice(0, 10)
                          : '-'}
                      </p>
                    </div>
                    <StatusBadge status={ex.status} />
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>
    </section>
  );
}

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-md bg-white p-2">
      <dt className="text-[10px] uppercase tracking-wide text-gray-500">
        {label}
      </dt>
      <dd className="text-body font-semibold tabular-nums text-gray-800">
        {children}
      </dd>
    </div>
  );
}
