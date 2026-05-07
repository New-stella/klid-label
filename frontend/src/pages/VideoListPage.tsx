import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import { DataTable, type DataTableColumn } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusBadge } from '@/components/common/StatusBadge';
import { AssignModal } from '@/features/task/components/AssignModal';
import { VideoActions } from '@/features/video/components/VideoActions';
import { VideoFilters } from '@/features/video/components/VideoFilters';
import { useVideos } from '@/features/video/hooks/useVideos';
import {
  parseVideoListParams,
  videoListParamsToSearchParams,
} from '@/features/video/parseVideoListParams';
import type { Video, VideoListParams } from '@/features/video/types';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * SCR-VIDEO-001 영상 목록.
 * 검색·필터 + DataTable + URL 파라미터 동기화 (뒤로가기 유지).
 *
 * 보안: 검색 입력은 URL 인코딩되어 axios params로 전달 (XSS 방지).
 */
export function VideoListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const params = useMemo(() => parseVideoListParams(searchParams), [searchParams]);
  const { data, isLoading, error } = useVideos(params);
  const role = useAuthStore((s) => s.claims?.role);
  const isReviewer = role === Role.REVIEWER;
  const [assignTarget, setAssignTarget] = useState<Video | null>(null);

  const updateParams = (next: VideoListParams) => {
    const sp = videoListParamsToSearchParams({ ...params, ...next });
    setSearchParams(sp, { replace: false });
  };

  const columns: DataTableColumn<Video>[] = [
    { key: 'cctvName', header: 'CCTV명/파일명', render: (v) => v.cctvName },
    { key: 'eventName', header: '이벤트', render: (v) => v.eventName },
    { key: 'localGov', header: '지자체', render: (v) => v.localGov },
    {
      key: 'frameCount',
      header: '프레임수',
      align: 'right',
      render: (v) => v.frameCount.toLocaleString('ko-KR'),
    },
    {
      key: 'status',
      header: '상태',
      render: (v) => <StatusBadge status={v.status} />,
    },
    {
      key: 'actions',
      header: '액션',
      render: (v) => (
        <VideoActions
          video={v}
          canAssign={isReviewer}
          canRequestBg={isReviewer}
          onAssign={(video) => setAssignTarget(video)}
        />
      ),
    },
  ];

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="영상 목록"
        description="배치 처리가 완료된 영상 목록입니다."
      />
      <VideoFilters initial={params} onApply={updateParams} />
      {error && <ErrorState title="영상 목록을 불러올 수 없습니다" />}
      <DataTable<Video>
        columns={columns}
        rows={data?.content ?? []}
        totalElements={data?.totalElements ?? 0}
        page={params.page ?? 0}
        size={params.size ?? 20}
        sort={params.sort}
        loading={isLoading}
        emptyMessage="조건에 맞는 영상이 없습니다"
        rowKey={(v) => v.id}
        onPageChange={(p) => updateParams({ page: p })}
        onSortChange={(s) => updateParams({ sort: s, page: 0 })}
      />
      {assignTarget && (
        <AssignModal video={assignTarget} onClose={() => setAssignTarget(null)} />
      )}
    </section>
  );
}
