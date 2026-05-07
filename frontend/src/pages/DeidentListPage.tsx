import { useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { DataTable, type DataTableColumn } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Select } from '@/components/common/Select';
import { StatusBadge } from '@/components/common/StatusBadge';
import { reprocessDeident } from '@/features/deident/api';
import { useDeidentList } from '@/features/deident/hooks/useDeidentList';
import type {
  DeidentListParams,
  DeidentListRow,
  DeidentStatus,
} from '@/features/deident/types';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCR-DEIDENT-001 비식별화 결과 목록.
 *
 * DataTable + 처리 상태/PRVC_YN 필터 + [재처리] 버튼.
 * 보안: 검색 입력 → axios params (XSS 방지). PRVC_YN='N' 행은 [재처리] 노출 X (액션 미노출).
 * IDOR: BE 검증.
 */
export function DeidentListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const role = useAuthStore((s) => s.claims?.role);
  const pushToast = useUiStore((s) => s.pushToast);
  const isReviewer = role === Role.REVIEWER;

  const params = useMemo((): DeidentListParams => {
    const page = Number.parseInt(searchParams.get('page') ?? '0', 10);
    const size = Number.parseInt(searchParams.get('size') ?? '20', 10);
    const prvcYn = searchParams.get('prvcYn') ?? '';
    const status = searchParams.get('status') ?? '';
    return {
      page: Number.isFinite(page) && page >= 0 ? page : 0,
      size: Number.isFinite(size) && size > 0 && size <= 100 ? size : 20,
      prvcYn: prvcYn === 'Y' || prvcYn === 'N' ? prvcYn : '',
      status:
        status === 'PENDING' ||
        status === 'IN_PROGRESS' ||
        status === 'COMPLETED' ||
        status === 'FAILED'
          ? (status as DeidentStatus)
          : '',
    };
  }, [searchParams]);

  const { data, isLoading, error, refetch } = useDeidentList(params);

  const updateParams = (next: Partial<DeidentListParams>) => {
    const merged: Record<string, string> = {};
    const final = { ...params, ...next };
    if ((final.page ?? 0) > 0) merged.page = String(final.page);
    if ((final.size ?? 20) !== 20) merged.size = String(final.size);
    if (final.prvcYn) merged.prvcYn = final.prvcYn;
    if (final.status) merged.status = final.status;
    setSearchParams(merged, { replace: false });
  };

  const handleReprocess = async (videoId: number) => {
    try {
      await reprocessDeident(videoId);
      pushToast({ variant: 'success', message: '재처리 요청이 전송되었습니다.' });
      refetch();
    } catch {
      pushToast({ variant: 'error', message: '재처리 요청에 실패했습니다.' });
    }
  };

  const columns: DataTableColumn<DeidentListRow>[] = [
    { key: 'cctvName', header: 'CCTV명', render: (r) => r.cctvName },
    { key: 'vmsClipId', header: 'Clip ID', render: (r) => r.vmsClipId },
    {
      key: 'prvcType',
      header: '개인정보 유형',
      render: (r) => r.prvcType,
    },
    {
      key: 'prvcYn',
      header: '비식별 대상',
      render: (r) => (r.prvcYn === 'Y' ? '대상' : '비대상'),
    },
    {
      key: 'status',
      header: '상태',
      render: (r) => <StatusBadge status={mapStatus(r.status)} />,
    },
    {
      key: 'progress',
      header: '진행',
      align: 'right',
      render: (r) =>
        `${r.processedFrames.toLocaleString('ko-KR')} / ${r.totalFrames.toLocaleString('ko-KR')}${
          r.failedFrames > 0 ? ` (실패 ${r.failedFrames})` : ''
        }`,
    },
    {
      key: 'actions',
      header: '액션',
      render: (r) => (
        <div
          className="flex items-center gap-1"
          data-testid={`deident-actions-${r.videoId}`}
        >
          <Button
            size="sm"
            variant="ghost"
            onClick={() => navigate(`/deident/${r.videoId}`)}
          >
            상세
          </Button>
          {isReviewer && r.prvcYn === 'Y' && (
            <Button
              size="sm"
              variant="outline"
              onClick={() => handleReprocess(r.videoId)}
              data-testid={`reprocess-btn-${r.videoId}`}
            >
              재처리
            </Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="비식별화 결과"
        description="비식별화 처리가 완료되었거나 실패한 영상 목록입니다."
      />

      <div
        data-testid="deident-filters"
        className="grid grid-cols-1 gap-3 rounded border border-border bg-white p-4 sm:grid-cols-3"
      >
        <Select
          label="비식별 대상 (PRVC_YN)"
          value={params.prvcYn ?? ''}
          options={[
            { value: '', label: '전체' },
            { value: 'Y', label: '대상 (Y)' },
            { value: 'N', label: '비대상 (N)' },
          ]}
          onChange={(e) =>
            updateParams({
              prvcYn: (e.target.value as 'Y' | 'N' | '') || '',
              page: 0,
            })
          }
        />
        <Select
          label="처리 상태"
          value={params.status ?? ''}
          options={[
            { value: '', label: '전체' },
            { value: 'PENDING', label: '대기' },
            { value: 'IN_PROGRESS', label: '진행중' },
            { value: 'COMPLETED', label: '완료' },
            { value: 'FAILED', label: '실패' },
          ]}
          onChange={(e) =>
            updateParams({
              status: (e.target.value as DeidentStatus | '') || '',
              page: 0,
            })
          }
        />
      </div>

      {error && <ErrorState title="비식별화 목록을 불러올 수 없습니다" />}
      <DataTable<DeidentListRow>
        columns={columns}
        rows={data?.content ?? []}
        totalElements={data?.totalElements ?? 0}
        page={params.page ?? 0}
        size={params.size ?? 20}
        loading={isLoading}
        emptyMessage="조건에 맞는 비식별 결과가 없습니다"
        rowKey={(r) => r.videoId}
        onPageChange={(p) => updateParams({ page: p })}
      />
    </section>
  );
}

function mapStatus(s: DeidentStatus): 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'BATCH_FAILED' {
  switch (s) {
    case 'COMPLETED':
      return 'COMPLETED';
    case 'IN_PROGRESS':
      return 'IN_PROGRESS';
    case 'FAILED':
      return 'BATCH_FAILED';
    case 'PENDING':
    default:
      return 'PENDING';
  }
}
