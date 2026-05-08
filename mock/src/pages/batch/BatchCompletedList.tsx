import { useState, useCallback, useEffect } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { Table } from '../../components/ui/Table';
import { Badge } from '../../components/ui/Badge';
import { Pagination } from '../../components/ui/Pagination';
import { EventTypeBadge } from '../../components/batch/EventTypeBadge';
import { BatchFilters, DEFAULT_FILTERS } from './BatchFilters';
import type { BatchFilterValues } from './BatchFilters';
import { useFetch } from '../../api/queries';
import type { VideoDto, Page, BatchStage, StageStatus } from '../../api/types';
import { formatDate, formatDuration, privacyTypeLabel, stageLabel } from '../../utils/format';
import type { ColumnDef } from '../../components/ui/Table';

function privacyTone(t: string): 'danger' | 'warning' | 'neutral' {
  if (t === 'PRVC') return 'danger';
  if (t === 'PSDO') return 'warning';
  return 'neutral';
}

/**
 * stage 이름·상태 조합으로 Badge 톤을 결정한다.
 * V1.8: 신규 진입 단계인 VLM은 다른 stage와 구분되는 보라 톤으로 표시한다.
 * 종료(DONE)/실패(FAIL)는 status 기반 색을 우선하고, 그 외에는 stage 정체성을 드러낸다.
 */
function stageBadgeTone(
  name: BatchStage,
  status: StageStatus,
): 'info' | 'success' | 'danger' | 'neutral' | 'purple' {
  if (status === 'DONE') return 'success';
  if (status === 'FAIL') return 'danger';
  if (name === 'VLM') return 'purple';
  if (status === 'PROGRESS') return 'info';
  return 'neutral';
}

/**
 * 영상의 현재 처리 단계를 산출한다.
 * - 진행 중(PROGRESS)이 있으면 그 단계
 * - 실패(FAIL)가 있으면 그 단계
 * - 모두 DONE이면 마지막 단계
 * - 아직 시작 전이면 첫 단계(대기)
 */
function currentStage(
  stages: { name: BatchStage; status: StageStatus; progress: number }[],
): { name: BatchStage; status: StageStatus } | null {
  if (!stages || stages.length === 0) return null;
  const progress = stages.find((s) => s.status === 'PROGRESS');
  if (progress) return { name: progress.name, status: progress.status };
  const fail = stages.find((s) => s.status === 'FAIL');
  if (fail) return { name: fail.name, status: fail.status };
  const allDone = stages.every((s) => s.status === 'DONE');
  if (allDone) return { name: stages[stages.length - 1].name, status: 'DONE' };
  return { name: stages[0].name, status: 'PENDING' };
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

  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState<BatchFilterValues>(() =>
    searchParamsToFilters(searchParams),
  );
  const [selected, setSelected] = useState<Set<string>>(new Set());

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
      header: '개인정보',
      render: (row) => (
        <Badge tone={privacyTone(row.privacyType)} size="sm">
          {privacyTypeLabel(row.privacyType)}
        </Badge>
      ),
    },
    {
      key: '_stage',
      header: '처리 단계',
      width: '120px',
      render: (row) => {
        const stage = currentStage(row.stages);
        if (!stage) return <span className="text-xs text-gray-400">-</span>;
        const allDone =
          row.stages.length > 0 && row.stages.every((s) => s.status === 'DONE');
        if (allDone) {
          return (
            <Badge tone="success" size="sm">
              완료
            </Badge>
          );
        }
        return (
          <Badge tone={stageBadgeTone(stage.name, stage.status)} size="sm">
            {stageLabel(stage.name)}
          </Badge>
        );
      },
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
        </div>
      ),
    },
  ];

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
    </div>
  );
}

export default BatchCompletedList;
