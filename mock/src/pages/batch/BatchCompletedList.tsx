import { useState, useCallback, useEffect } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { RefreshCw, UserPlus, Sparkles } from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { Table } from '../../components/ui/Table';
import { Badge } from '../../components/ui/Badge';
import { Pagination } from '../../components/ui/Pagination';
import { StatusBadge } from '../../components/batch/StatusBadge';
import { EventTypeBadge } from '../../components/batch/EventTypeBadge';
import { BackgroundGenerateModal } from '../../components/batch/BackgroundGenerateModal';
import { BatchFilters, DEFAULT_FILTERS } from './BatchFilters';
import type { BatchFilterValues } from './BatchFilters';
import { useFetch } from '../../api/queries';
import type { VideoDto, Page } from '../../api/types';
import { useSessionStore } from '../../store/sessionStore';
import { formatDate, formatDuration, privacyTypeLabel } from '../../utils/format';
import type { ColumnDef } from '../../components/ui/Table';

function privacyTone(t: string): 'danger' | 'warning' | 'neutral' {
  if (t === 'PRVC') return 'danger';
  if (t === 'PSDO') return 'warning';
  return 'neutral';
}

function searchParamsToFilters(sp: URLSearchParams): BatchFilterValues {
  return {
    q: sp.get('q') ?? '',
    status: sp.get('status') ?? '',
    eventType: sp.get('eventType') ?? '',
    dateFrom: sp.get('dateFrom') ?? '',
    dateTo: sp.get('dateTo') ?? '',
  };
}

function filtersToSearchParams(f: BatchFilterValues): Record<string, string> {
  const out: Record<string, string> = {};
  if (f.q) out['q'] = f.q;
  if (f.status) out['status'] = f.status;
  if (f.eventType) out['eventType'] = f.eventType;
  if (f.dateFrom) out['dateFrom'] = f.dateFrom;
  if (f.dateTo) out['dateTo'] = f.dateTo;
  return out;
}

export function BatchCompletedList() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { currentRole } = useSessionStore();
  const isReviewer = currentRole === 'REVIEWER';
  // 행별 액션 가능 여부: REVIEWER 권한 + 처리 완료 상태일 때만 활성화
  const canAssignRow = (video: VideoDto) =>
    isReviewer && video.batchStatus === 'COMPLETED';
  const canRequestBgGenRow = (video: VideoDto) =>
    isReviewer && video.batchStatus === 'COMPLETED';

  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState<BatchFilterValues>(() =>
    searchParamsToFilters(searchParams),
  );
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bgGenVideo, setBgGenVideo] = useState<VideoDto | null>(null);
  const [bgGenOpen, setBgGenOpen] = useState(false);

  // Build fetch params
  const fetchParams: Record<string, unknown> = {
    page,
    size: 20,
    ...(filters.q ? { q: filters.q } : {}),
    ...(filters.status ? { status: filters.status } : {}),
    ...(filters.eventType ? { eventType: filters.eventType } : {}),
  };

  const { data, isLoading, refetch } = useFetch<Page<VideoDto>>('/videos', fetchParams);

  // Sync filters → URL params
  useEffect(() => {
    const params = filtersToSearchParams(filters);
    setSearchParams(params, { replace: true });
    setPage(0);
    setSelected(new Set());
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters]);

  const handleFilterChange = useCallback((v: BatchFilterValues) => {
    setFilters(v);
  }, []);

  const handleReset = useCallback(() => {
    setFilters(DEFAULT_FILTERS);
  }, []);

  const handlePageChange = useCallback((p: number) => {
    setPage(p);
    setSelected(new Set());
  }, []);

  const rows = data?.content ?? [];
  const allChecked = rows.length > 0 && rows.every((r) => selected.has(r.id));

  const toggleAll = () => {
    if (allChecked) {
      setSelected(new Set());
    } else {
      setSelected(new Set(rows.map((r) => r.id)));
    }
  };

  const toggleRow = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const columns: ColumnDef<VideoDto>[] = [
    {
      key: '_check',
      header: '',
      width: '40px',
      render: (row) => (
        <input
          type="checkbox"
          checked={selected.has(row.id)}
          onChange={() => toggleRow(row.id)}
          onClick={(e) => e.stopPropagation()}
          className="w-4 h-4 accent-primary-600"
          aria-label="선택"
        />
      ),
    },
    {
      key: 'cctvName',
      header: 'CCTV명',
      render: (row) => (
        <span className="font-medium text-gray-800 text-xs">{row.cctvName}</span>
      ),
    },
    {
      key: 'eventType',
      header: '이벤트',
      render: (row) => <EventTypeBadge eventType={row.eventType} />,
    },
    {
      key: 'recordedAt',
      header: '녹화일',
      render: (row) => (
        <span className="text-xs text-gray-500">{formatDate(row.recordedAt, 'YYYY-MM-DD')}</span>
      ),
    },
    {
      key: 'durationSec',
      header: '길이',
      render: (row) => <span className="text-xs">{formatDuration(row.durationSec)}</span>,
    },
    {
      key: 'privacyType',
      header: '비식별',
      render: (row) => (
        <Badge tone={privacyTone(row.privacyType)} size="sm">
          {privacyTypeLabel(row.privacyType)}
        </Badge>
      ),
    },
    {
      key: 'batchStatus',
      header: '처리 상태',
      render: (row) => <StatusBadge status={row.batchStatus} />,
    },
    {
      key: '_actions',
      header: '액션',
      render: (row) => (
        <div className="flex gap-1" onClick={(e) => e.stopPropagation()}>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => navigate(`/video/${row.id}`)}
          >
            상세▶
          </Button>
          {isReviewer && (
            <Button
              variant="secondary"
              size="sm"
              leftIcon={UserPlus}
              disabled={!canAssignRow(row)}
              onClick={() => alert(`배정: ${row.id}`)}
              title={
                canAssignRow(row)
                  ? '작업자 배정'
                  : '처리 완료된 영상만 배정할 수 있습니다'
              }
            >
              배정
            </Button>
          )}
          {isReviewer && (
            <Button
              variant="secondary"
              size="sm"
              leftIcon={Sparkles}
              disabled={!canRequestBgGenRow(row)}
              onClick={(e) => {
                e.stopPropagation();
                setBgGenVideo(row);
                setBgGenOpen(true);
              }}
              title={
                canRequestBgGenRow(row)
                  ? '배경영상 생성 요청'
                  : '처리 완료된 영상만 배경영상을 요청할 수 있습니다'
              }
            >
              배경영상
            </Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-gray-900">영상 목록</h1>
        <Button variant="secondary" size="sm" leftIcon={RefreshCw} onClick={() => refetch()}>
          새로고침
        </Button>
      </div>

      {/* Filters */}
      <BatchFilters values={filters} onChange={handleFilterChange} onReset={handleReset} />

      {/* Bulk actions */}
      {selected.size > 0 && (
        <div className="flex items-center gap-3 bg-primary-50 border border-primary-200 rounded-lg px-4 py-2.5 text-sm">
          <span className="font-medium text-primary-700">선택 {selected.size}건</span>
          {isReviewer && (
            <Button
              variant="primary"
              size="sm"
              leftIcon={UserPlus}
              onClick={() => alert(`${selected.size}건 일괄 배정 (Phase 4에서 구현)`)}
            >
              일괄 배정
            </Button>
          )}
        </div>
      )}

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-lg shadow-sm overflow-hidden">
        {/* Header checkbox */}
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
            {data ? ` (${data.number + 1}/${data.totalPages} 페이지)` : ''}
          </span>
        </div>
        <Table<VideoDto>
          columns={columns}
          rows={rows}
          rowKey={(r) => r.id}
          onRowClick={(row) => navigate(`/video/${row.id}`)}
          loading={isLoading}
          emptyMessage="해당하는 영상이 없습니다."
        />
      </div>

      {/* Pagination */}
      {data && (
        <Pagination
          page={data.number}
          totalPages={data.totalPages}
          onChange={handlePageChange}
        />
      )}

      {/* Background generate modal */}
      <BackgroundGenerateModal
        open={bgGenOpen}
        video={bgGenVideo}
        onClose={() => {
          setBgGenOpen(false);
          setBgGenVideo(null);
        }}
      />
    </div>
  );
}

export default BatchCompletedList;
