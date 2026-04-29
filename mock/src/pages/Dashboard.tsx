import { useState, useEffect } from 'react';
import dayjs from 'dayjs';
import {
  Clock,
  Film,
  CheckCircle2,
  ClipboardList,
  XCircle,
  RefreshCw,
} from 'lucide-react';
import { StatCard } from '../components/ui/StatCard';
import { Card } from '../components/ui/Card';
import { Table } from '../components/ui/Table';
import { ProgressBar } from '../components/ui/ProgressBar';
import { StatusBadge } from '../components/batch/StatusBadge';
import { EventTypeBadge } from '../components/batch/EventTypeBadge';
import { useFetch } from '../api/queries';
import type { VideoDto, TaskDto, OverallStat, Page } from '../api/types';
import { useSessionStore } from '../store/sessionStore';
import { computeKpiStats, randomDelta } from './dashboard/useKpiStats';
import { formatDate, formatDuration, formatNumber } from '../utils/format';
import type { ColumnDef } from '../components/ui/Table';
import { Button } from '../components/ui/Button';
import { Skeleton } from '../components/ui/Skeleton';

function NowClock() {
  const [now, setNow] = useState(() => dayjs());
  useEffect(() => {
    const t = setInterval(() => setNow(dayjs()), 1000);
    return () => clearInterval(t);
  }, []);
  return (
    <span className="text-sm text-gray-500 flex items-center gap-1">
      <Clock size={14} />
      {now.format('YYYY-MM-DD HH:mm:ss')}
    </span>
  );
}

export function Dashboard() {
  const { currentRole, currentUser } = useSessionStore();
  const sessionRole = currentRole as 'REVIEWER' | 'WORKER';

  // KPI: fetch all videos (large page)
  const { data: videosPage, isLoading: videosLoading, refetch: refetchVideos } =
    useFetch<Page<VideoDto>>('/videos', { size: 200, page: 0 });

  // Overall stat
  const { data: overallStat, isLoading: statLoading } =
    useFetch<OverallStat>('/stats/overall');

  // Recent completed videos
  const { data: recentPage, isLoading: recentLoading } =
    useFetch<Page<VideoDto>>('/videos', { status: 'COMPLETED', size: 5, page: 0 });

  const isWorker = sessionRole === 'WORKER';

  // My tasks — fetched only when WORKER; hook called unconditionally (rules of hooks)
  const { data: taskPage, isLoading: taskLoading } =
    useFetch<Page<TaskDto>>(
      '/tasks',
      isWorker ? { assigneeId: currentUser.id, size: 5, page: 0 } : { size: 0, page: 0 },
    );

  const kpi = videosPage
    ? computeKpiStats(videosPage.content, currentUser.id, sessionRole)
    : null;

  // Recent completed video table columns
  const videoColumns: ColumnDef<VideoDto>[] = [
    {
      key: 'cctvName',
      header: 'CCTV명',
      render: (row) => <span className="font-medium text-gray-800 text-xs">{row.cctvName}</span>,
    },
    {
      key: 'eventType',
      header: '이벤트',
      render: (row) => <EventTypeBadge eventType={row.eventType} />,
    },
    {
      key: 'durationSec',
      header: '길이',
      render: (row) => <span className="text-xs">{formatDuration(row.durationSec)}</span>,
    },
    {
      key: 'updatedAt',
      header: '완료일',
      render: (row) => <span className="text-xs text-gray-500">{formatDate(row.updatedAt, 'MM-DD HH:mm')}</span>,
    },
  ];

  // My task table columns
  const taskColumns: ColumnDef<TaskDto>[] = [
    {
      key: 'videoName',
      header: '영상',
      render: (row) => <span className="font-medium text-gray-800 text-xs">{row.videoName}</span>,
    },
    {
      key: 'status',
      header: '상태',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'progress',
      header: '진행률',
      render: (row) => (
        <div className="flex items-center gap-2 min-w-[80px]">
          <ProgressBar value={row.progress} size="sm" className="flex-1" />
          <span className="text-xs text-gray-500 w-8 tabular-nums">{row.progress}%</span>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-gray-900">대시보드</h1>
        <div className="flex items-center gap-3">
          <NowClock />
          <Button
            variant="secondary"
            size="sm"
            leftIcon={RefreshCw}
            onClick={() => refetchVideos()}
          >
            새로고침
          </Button>
        </div>
      </div>

      {/* KPI 카드: WORKER 4개, REVIEWER 3개 */}
      {videosLoading || !kpi ? (
        <div className={`grid grid-cols-2 ${isWorker ? 'xl:grid-cols-4' : 'xl:grid-cols-3'} gap-4`}>
          {Array.from({ length: isWorker ? 4 : 3 }).map((_, i) => (
            <Skeleton key={i} height="6rem" className="rounded-lg" />
          ))}
        </div>
      ) : (
        <div className={`grid grid-cols-2 ${isWorker ? 'xl:grid-cols-4' : 'xl:grid-cols-3'} gap-4`}>
          <StatCard
            label="처리 대기"
            value={formatNumber(kpi.pending)}
            icon={Film}
            tone="warning"
            delta={randomDelta(1)}
          />
          <StatCard
            label="처리 완료"
            value={formatNumber(kpi.completed)}
            icon={CheckCircle2}
            tone="success"
            delta={randomDelta(2)}
          />
          {isWorker && (
            <StatCard
              label="내 작업"
              value={formatNumber(kpi.myWork)}
              icon={ClipboardList}
              tone="primary"
              delta={randomDelta(3)}
            />
          )}
          <StatCard
            label="반려 건수"
            value={formatNumber(kpi.rejected)}
            icon={XCircle}
            tone="danger"
            delta={randomDelta(0)}
          />
        </div>
      )}

      {/* 데이터 개수 섹션 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <Card title="이미지 데이터 개수">
          {statLoading || !overallStat ? (
            <Skeleton height="3rem" />
          ) : (
            <div className="space-y-3">
              <div className="text-2xl font-semibold text-primary-600">
                {formatNumber(overallStat.imageCompleted)}장
              </div>
              <div className="border-t border-gray-200 pt-3">
                <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
                  {overallStat.imageByEvent.map((item) => (
                    <div key={item.eventType} className="flex justify-between text-xs">
                      <span className="text-gray-500">{item.eventType}</span>
                      <span className="tabular-nums font-medium text-gray-800">
                        {item.count.toLocaleString()}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </Card>

        <Card title="영상 데이터 개수">
          {statLoading || !overallStat ? (
            <Skeleton height="3rem" />
          ) : (
            <div className="space-y-3">
              <div className="text-2xl font-semibold text-primary-600">
                {formatNumber(overallStat.videoCompleted)}건
              </div>
              <div className="border-t border-gray-200 pt-3">
                <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
                  {overallStat.videoByEvent.map((item) => (
                    <div key={item.eventType} className="flex justify-between text-xs">
                      <span className="text-gray-500">{item.eventType}</span>
                      <span className="tabular-nums font-medium text-gray-800">
                        {item.count.toLocaleString()}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </Card>
      </div>

      {/* Bottom section: recent completed + my tasks (my tasks: WORKER only) */}
      <div className={`grid grid-cols-1 ${isWorker ? 'xl:grid-cols-2' : ''} gap-4`}>
        <Card title="최근 완료 영상">
          <Table<VideoDto>
            columns={videoColumns}
            rows={recentPage?.content ?? []}
            rowKey={(r) => r.id}
            loading={recentLoading}
            emptyMessage="완료된 영상이 없습니다."
          />
        </Card>

        {isWorker && (
          <Card title="내 작업 현황">
            <Table<TaskDto>
              columns={taskColumns}
              rows={taskPage?.content ?? []}
              rowKey={(r) => r.id}
              loading={taskLoading}
              emptyMessage="작업이 없습니다."
            />
          </Card>
        )}
      </div>
    </div>
  );
}

export default Dashboard;
