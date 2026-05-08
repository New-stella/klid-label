import { useEffect, useMemo, useState, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Send,
  RefreshCw,
  FileJson,
  Eye,
  Server,
  ShieldCheck,
  CheckCircle2,
  XCircle,
  Info,
  Search,
  RotateCcw,
} from 'lucide-react';
import { useFetch, useMutation } from '../../api/queries';
import { api } from '../../api/client';
import type {
  ExportFormat,
  ExportRequest,
  ExportResponse,
  Page,
  VideoDto,
} from '../../api/types';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { Pagination } from '../../components/ui/Pagination';
import { Skeleton } from '../../components/ui/Skeleton';
import { useToast } from '../../components/common/Toast';
import { EventTypeBadge } from '../../components/batch/EventTypeBadge';
import { formatDate } from '../../utils/format';

const FORMAT_META: Record<
  ExportFormat,
  { label: string; description: string; extension: string }
> = {
  COCO: {
    label: 'COCO',
    description: 'JSON 포맷 — 바운딩박스·세그멘테이션 모두 지원. 객체 탐지 표준.',
    extension: '.json',
  },
  YOLO: {
    label: 'YOLO',
    description: 'TXT 포맷 — 정규화된 좌표. YOLO v5/v8 학습에 최적화.',
    extension: '.txt',
  },
  CVAT: {
    label: 'CVAT',
    description: 'XML 포맷 — CVAT 호환 어노테이션. 폴리곤·트랙 포함.',
    extension: '.xml',
  },
  PASCAL_VOC: {
    label: 'Pascal VOC',
    description: 'XML 포맷 — 이미지별 개별 XML. 전통 객체 탐지 포맷.',
    extension: '.xml',
  },
};

const FORMAT_PREVIEW: Record<ExportFormat, string> = {
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

const PAGE_SIZE = 100;
const PORTAL_ENDPOINT = 'https://portal.example.com/api/v1/datasets/import';

type ExportFilterValue = 'ALL' | 'NEVER' | 'EXPORTED' | 'FAILED';

const EXPORT_FILTER_OPTIONS: { value: ExportFilterValue; label: string }[] = [
  { value: 'ALL', label: '전체' },
  { value: 'NEVER', label: '미전송' },
  { value: 'EXPORTED', label: '이미 전송됨' },
  { value: 'FAILED', label: '전송 실패' },
];

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

export function ExportPage() {
  const { showToast } = useToast();
  const [searchParams, setSearchParams] = useSearchParams();

  const [format, setFormat] = useState<ExportFormat>('COCO');
  const [page, setPage] = useState(0);
  const [selectedVideoIds, setSelectedVideoIds] = useState<Set<string>>(new Set());
  const [includeLabels, setIncludeLabels] = useState(true);
  const [includeImages, setIncludeImages] = useState(true);
  const [deidentify, setDeidentify] = useState(true);
  const [forceReexport, setForceReexport] = useState(false);

  // 필터 상태 — applied(URL 동기화) / local(폼 입력)
  const [filters, setFilters] = useState<VideoFilterValues>(() =>
    searchParamsToFilters(searchParams),
  );
  const [localFilters, setLocalFilters] = useState<VideoFilterValues>(filters);

  // 핸들러는 `status` 한 개만 처리하므로 batchStatus===COMPLETED + taskStatus===COMPLETED가 섞여 응답됨.
  // 충분히 받은 뒤 client-side에서 검수 완료 영상(taskStatus===COMPLETED)만 노출하고 client-side로 페이징.
  const { data, isLoading } = useFetch<Page<VideoDto>>('/videos', {
    status: 'COMPLETED',
    page: 0,
    size: 999,
  });

  const { mutate, isLoading: isExporting } = useMutation<ExportRequest, ExportResponse>(
    (body) => api.post<ExportResponse>('/export', body),
  );

  // 내보내기는 검수 완료(승인) 영상만 가능 — taskStatus === 'COMPLETED' 가드
  const approvedVideos = useMemo(
    () => (data?.content ?? []).filter((v) => v.taskStatus === 'COMPLETED'),
    [data?.content],
  );

  // 이벤트 유형 옵션 (검수 완료 영상에서 unique, 정렬)
  const eventTypeOptions = useMemo(() => {
    const set = new Set<string>();
    approvedVideos.forEach((v) => {
      if (v.eventType) set.add(v.eventType);
    });
    return Array.from(set).sort();
  }, [approvedVideos]);

  // 검색·이벤트·전송상태 필터 적용
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
    if (filters.exportFilter !== 'ALL') {
      result = result.filter((v) => {
        const status = v.exportStatus ?? 'NEVER';
        return status === filters.exportFilter;
      });
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

  // 페이지 범위 보정
  useEffect(() => {
    if (page >= totalPages) setPage(0);
  }, [page, totalPages]);

  // 행이 disabled 되는지 판정 — 이미 EXPORTED인데 forceReexport OFF
  const isRowDisabled = useCallback(
    (video: VideoDto): boolean =>
      !forceReexport && video.exportStatus === 'EXPORTED',
    [forceReexport],
  );

  // 현재 페이지의 선택 가능 영상 (전체 선택 토글에서 사용)
  const selectablePagedVideos = useMemo(
    () => pagedVideos.filter((v) => !isRowDisabled(v)),
    [pagedVideos, isRowDisabled],
  );

  // 현재 페이지의 선택 가능 ID
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

  // 필터/페이지 변경 시 보이지 않는 항목은 선택에서 자동 해제 (filteredVideos 기준 — 페이지 바뀌어도 선택 유지)
  useEffect(() => {
    setSelectedVideoIds((prev) => {
      const visibleIds = new Set(filteredVideos.map((v) => v.id));
      const next = new Set<string>();
      for (const id of prev) if (visibleIds.has(id)) next.add(id);
      return next.size === prev.size ? prev : next;
    });
  }, [filteredVideos]);

  const toggleVideo = (video: VideoDto) => {
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

  // forceReexport OFF로 토글 시 EXPORTED 영상 선택 자동 해제 (전체 승인 영상 기준)
  const handleToggleForceReexport = (next: boolean) => {
    setForceReexport(next);
    if (!next) {
      setSelectedVideoIds((prev) => {
        const filtered = new Set<string>();
        for (const id of prev) {
          const v = approvedVideos.find((x) => x.id === id);
          if (v && v.exportStatus !== 'EXPORTED') filtered.add(id);
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

  const handleExport = async () => {
    if (selectedVideoIds.size === 0) {
      showToast('대상 영상을 선택해주세요.', 'error');
      return;
    }
    try {
      const result = await mutate({
        format,
        videoIds: Array.from(selectedVideoIds),
        options: {
          includeLabels,
          includeImages,
        },
        deidentify,
        targetServer: 'PORTAL',
        forceReexport,
      });

      // 1차 메시지 — count=0이면 info(경고 톤), 아니면 success
      if (result.count === 0) {
        showToast(
          result.message ?? '전송 가능한 영상이 없습니다',
          'info',
        );
      } else {
        showToast(`${result.count}건 포털 전송 시작됨`, 'success');
      }

      // 2차 안내 — 시도 결과 추가 토스트
      const failedDeident = result.failedDueDeident ?? 0;
      const skipped = result.skippedAlreadyExported ?? 0;
      const blockedNotApproved = result.blockedNotApproved ?? 0;
      if (failedDeident > 0) {
        showToast(
          `비식별 실패 ${failedDeident}건 (다시 시도 가능)`,
          'info',
        );
      }
      if (skipped > 0) {
        showToast(
          `이미 내보낸 ${skipped}건은 스킵되었습니다 (강제 재전송으로 다시 보낼 수 있음)`,
          'info',
        );
      }
      if (blockedNotApproved > 0) {
        showToast(
          `검수 미완료 ${blockedNotApproved}건은 차단되었습니다`,
          'info',
        );
      }

      setSelectedVideoIds(new Set());
    } catch {
      showToast('포털 전송 실패', 'error');
    }
  };

  const isFilterActive =
    filters.q !== '' || filters.eventType !== '' || filters.exportFilter !== 'ALL';

  return (
    <div className="p-6 space-y-8">
      {/* Header */}
      <div className="flex items-start gap-3">
        <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-green-50 shrink-0">
          <Send size={20} className="text-green-600" />
        </div>
        <div>
          <h1 className="text-xl font-bold text-gray-900">내보내기</h1>
          <p className="text-xs text-gray-500 mt-0.5">
            포털 서버로 학습데이터셋을 전송합니다
          </p>
        </div>
      </div>

      {/* 안내 문구 — 검수 완료 영상만 내보내기 가능 */}
      <div className="flex items-start gap-2 bg-blue-50 border border-blue-200 rounded-lg px-3 py-2.5">
        <Info size={14} className="text-blue-600 mt-0.5 shrink-0" />
        <p className="text-xs text-blue-700">
          내보내기는 검수 완료(승인)된 영상만 가능합니다. 검수 미완료 영상은 목록에 표시되지
          않습니다.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left column: 3-step form */}
        <div className="lg:col-span-2 space-y-6">

          {/* Step 1: Format selection */}
          <section className="bg-white border border-gray-200 rounded-lg p-5 space-y-4">
            <div className="flex items-center gap-2">
              <span className="flex items-center justify-center w-6 h-6 rounded-full bg-primary-600 text-white text-xs font-bold shrink-0">
                1
              </span>
              <h2 className="text-sm font-semibold text-gray-800">형식 선택</h2>
            </div>

            <div className="grid grid-cols-2 gap-3">
              {(Object.keys(FORMAT_META) as ExportFormat[]).map((f) => {
                const meta = FORMAT_META[f];
                const selected = format === f;
                return (
                  <label
                    key={f}
                    className={[
                      'flex items-start gap-3 p-3 rounded-lg border-2 cursor-pointer transition-all',
                      selected
                        ? 'border-primary-500 bg-primary-50'
                        : 'border-gray-200 hover:border-gray-300 bg-white',
                    ].join(' ')}
                  >
                    <input
                      type="radio"
                      name="format"
                      value={f}
                      checked={selected}
                      onChange={() => setFormat(f)}
                      className="mt-0.5 w-4 h-4 text-primary-600 border-gray-300 focus:ring-primary-500"
                    />
                    <div>
                      <p className="text-sm font-semibold text-gray-800">
                        {meta.label}
                        <span className="ml-1 text-xs text-gray-400 font-normal">
                          {meta.extension}
                        </span>
                      </p>
                      <p className="text-xs text-gray-500 mt-0.5">{meta.description}</p>
                    </div>
                  </label>
                );
              })}
            </div>
          </section>

          {/* Step 2: Video selection */}
          <section className="bg-white border border-gray-200 rounded-lg p-5 space-y-4">
            <div className="flex items-center justify-between flex-wrap gap-2">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="flex items-center justify-center w-6 h-6 rounded-full bg-primary-600 text-white text-xs font-bold shrink-0">
                  2
                </span>
                <h2 className="text-sm font-semibold text-gray-800">대상 영상</h2>
                <Badge tone="neutral" size="sm">
                  선택 가능 {filteredVideos.length}건
                </Badge>
                {selectedVideoIds.size > 0 && (
                  <Badge tone="success" size="sm">
                    {selectedVideoIds.size}건 선택
                  </Badge>
                )}
              </div>

              {/* 이미 내보낸 영상 포함 토글 */}
              <label className="flex items-center gap-2 text-xs text-gray-600 cursor-pointer">
                <input
                  type="checkbox"
                  checked={forceReexport}
                  onChange={(e) => handleToggleForceReexport(e.target.checked)}
                  className="w-3.5 h-3.5 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span>이미 내보낸 영상 포함 (강제 재전송)</span>
              </label>
            </div>

            {/* 검색·필터 폼 */}
            <form
              onSubmit={handleApplyFilters}
              className="bg-gray-50 border border-gray-200 rounded-lg px-3 py-3 flex flex-wrap items-end gap-3"
            >
              <div className="flex flex-col gap-1 min-w-[180px] flex-1">
                <label className="text-xs font-medium text-gray-500">
                  영상명 / CCTV / ID
                </label>
                <div className="relative">
                  <Search
                    size={13}
                    className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400"
                  />
                  <input
                    type="text"
                    value={localFilters.q}
                    onChange={(e) =>
                      setLocalFilters((p) => ({ ...p, q: e.target.value }))
                    }
                    placeholder="검색어 입력"
                    className="w-full pl-7 pr-3 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white"
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
                  className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white"
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
                <label className="text-xs font-medium text-gray-500">전송 상태</label>
                <select
                  value={localFilters.exportFilter}
                  onChange={(e) => {
                    const v = e.target.value;
                    if (isExportFilterValue(v)) {
                      setLocalFilters((p) => ({ ...p, exportFilter: v }));
                    }
                  }}
                  className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white"
                >
                  {EXPORT_FILTER_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
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

            {isLoading ? (
              <div className="space-y-2">
                {Array.from({ length: 5 }, (_, i) => (
                  <Skeleton key={i} height="2.5rem" />
                ))}
              </div>
            ) : (
              <>
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-200">
                      <th className="text-left py-2 pr-3 w-10">
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
                          className="w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                          aria-label="현재 페이지 전체 선택"
                        />
                      </th>
                      <th className="text-left py-2 font-medium text-gray-600">
                        영상명 / CCTV
                      </th>
                      <th className="text-left py-2 font-medium text-gray-600">
                        이벤트
                      </th>
                      <th className="text-left py-2 font-medium text-gray-600">
                        녹화일
                      </th>
                      <th className="text-left py-2 font-medium text-gray-600">
                        전송 상태
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {pagedVideos.map((video) => {
                      const checked = selectedVideoIds.has(video.id);
                      const disabled = isRowDisabled(video);
                      return (
                        <tr
                          key={video.id}
                          className={[
                            'border-b border-gray-100',
                            disabled
                              ? 'opacity-50 cursor-not-allowed'
                              : 'hover:bg-gray-50 cursor-pointer',
                          ].join(' ')}
                          onClick={() => toggleVideo(video)}
                        >
                          <td className="py-2 pr-3">
                            <input
                              type="checkbox"
                              checked={checked}
                              disabled={disabled}
                              onChange={() => toggleVideo(video)}
                              onClick={(e) => e.stopPropagation()}
                              className="w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500 disabled:cursor-not-allowed"
                            />
                          </td>
                          <td className="py-2 max-w-[260px]">
                            <p className="font-medium text-gray-800 text-sm truncate">
                              {video.cctvName}
                            </p>
                            <p className="text-xs text-gray-400 font-mono truncate">
                              {video.id}
                            </p>
                          </td>
                          <td className="py-2">
                            {video.eventType ? (
                              <EventTypeBadge eventType={video.eventType} />
                            ) : (
                              <span className="text-xs text-gray-400">-</span>
                            )}
                          </td>
                          <td className="py-2 text-xs text-gray-500">
                            {formatDate(video.recordedAt, 'YYYY-MM-DD')}
                          </td>
                          <td className="py-2">
                            <ExportStatusBadge video={video} />
                          </td>
                        </tr>
                      );
                    })}
                    {pagedVideos.length === 0 && (
                      <tr>
                        <td
                          colSpan={5}
                          className="py-10 text-center text-gray-400 text-xs"
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

                {totalPages > 1 && (
                  <div className="flex items-center justify-between flex-wrap gap-2">
                    <span className="text-xs text-gray-500">
                      전체 {filteredVideos.length}건 ({page + 1}/{totalPages} 페이지)
                    </span>
                    <Pagination
                      page={page}
                      totalPages={totalPages}
                      onChange={setPage}
                    />
                  </div>
                )}
              </>
            )}
          </section>

          {/* Step 3: Options */}
          <section className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
            <div className="flex items-center gap-2">
              <span className="flex items-center justify-center w-6 h-6 rounded-full bg-primary-600 text-white text-xs font-bold shrink-0">
                3
              </span>
              <h2 className="text-sm font-semibold text-gray-800">옵션</h2>
            </div>

            {/* Toggle options */}
            <div className="space-y-3">
              <label className="flex items-center gap-3 text-sm cursor-pointer">
                <input
                  type="checkbox"
                  checked={includeLabels}
                  onChange={(e) => setIncludeLabels(e.target.checked)}
                  className="w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span className="text-gray-700 font-medium">라벨 포함</span>
                <span className="text-xs text-gray-400">어노테이션 파일 포함</span>
              </label>

              <label className="flex items-center gap-3 text-sm cursor-pointer">
                <input
                  type="checkbox"
                  checked={includeImages}
                  onChange={(e) => setIncludeImages(e.target.checked)}
                  className="w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span className="text-gray-700 font-medium">이미지 포함</span>
                <span className="text-xs text-gray-400">프레임 이미지 파일 포함</span>
              </label>

              {/* 비식별 처리 (기본 ON) */}
              <label className="flex items-start gap-3 text-sm cursor-pointer">
                <input
                  type="checkbox"
                  checked={deidentify}
                  onChange={(e) => setDeidentify(e.target.checked)}
                  className="mt-0.5 w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <div className="flex flex-col">
                  <div className="flex items-center gap-1.5">
                    <ShieldCheck size={14} className="text-primary-600" />
                    <span className="text-gray-700 font-medium">
                      비식별 처리 (개인정보 마스킹)
                    </span>
                  </div>
                  <span className="text-xs text-gray-400 mt-0.5">
                    체크 해제 시 원본 그대로 전송됩니다
                  </span>
                </div>
              </label>
            </div>

            {/* 포털 endpoint 정보 */}
            <div className="border-t border-gray-100 pt-4">
              <div className="flex items-start gap-2 bg-gray-50 rounded-lg px-3 py-2.5">
                <Server size={14} className="text-gray-400 mt-0.5 shrink-0" />
                <div className="flex-1 min-w-0">
                  <p className="text-xs font-medium text-gray-600">전송 대상</p>
                  <p className="text-xs text-gray-500 mt-0.5">
                    포털 서버
                    <span className="ml-1 font-mono text-gray-400 break-all">
                      ({PORTAL_ENDPOINT})
                    </span>
                  </p>
                </div>
              </div>
            </div>
          </section>
        </div>

        {/* Right column: Preview */}
        <div className="space-y-4">
          <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-3 sticky top-6">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 text-sm font-semibold text-gray-700">
                <Eye size={15} />
                <span>미리보기 — {FORMAT_META[format].label}</span>
              </div>
              <Badge tone="neutral" size="sm">{FORMAT_META[format].extension}</Badge>
            </div>

            <div className="flex items-center gap-1.5 text-xs text-gray-400">
              <FileJson size={13} />
              <span>샘플 출력 형식</span>
            </div>

            <pre className="bg-gray-50 rounded-lg p-3 text-xs text-gray-700 overflow-x-auto leading-relaxed max-h-80 overflow-y-auto scrollbar-thin">
              {FORMAT_PREVIEW[format]}
            </pre>

            <Button
              variant="secondary"
              size="sm"
              leftIcon={RefreshCw}
              className="w-full"
              onClick={() =>
                showToast(`${FORMAT_META[format].label} 미리보기 새로고침`, 'info')
              }
            >
              미리보기 새로고침
            </Button>

            <Button
              variant="primary"
              size="md"
              leftIcon={Send}
              loading={isExporting}
              className="w-full"
              onClick={() => { void handleExport(); }}
            >
              포털 전송 실행
            </Button>

            <p className="text-xs text-gray-400 text-center">
              선택된 영상 {selectedVideoIds.size}건
              {deidentify && (
                <span className="ml-1 text-primary-600">· 비식별 처리</span>
              )}
              {forceReexport && (
                <span className="ml-1 text-amber-600">· 강제 재전송</span>
              )}
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * 전송 이력 배지 — NEVER는 표시 없음, EXPORTED는 녹색+날짜.
 * FAILED는 lastExportFailureReason을 빨강 배지로 표기 (비식별 실패/포털 응답 오류 등).
 * 사유가 없으면 단순 "전송 실패"로 fallback.
 */
function ExportStatusBadge({ video }: { video: VideoDto }) {
  if (video.exportStatus === 'EXPORTED') {
    const dateStr = video.exportedAt ? video.exportedAt.slice(0, 10) : '';
    return (
      <Badge tone="success" size="sm" className="gap-1">
        <CheckCircle2 size={11} />
        전송됨
        {dateStr && <span className="ml-1 text-[10px] font-normal opacity-80">{dateStr}</span>}
      </Badge>
    );
  }
  if (video.exportStatus === 'FAILED') {
    const reason = video.lastExportFailureReason ?? '전송 실패';
    return (
      <Badge tone="danger" size="sm" className="gap-1">
        <XCircle size={11} />
        {reason}
      </Badge>
    );
  }
  return <span className="text-xs text-gray-300">—</span>;
}

export default ExportPage;
