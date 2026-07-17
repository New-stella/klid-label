import { useEffect, useState } from 'react';
import dayjs from 'dayjs';
import {
  CheckCircle2,
  ClipboardList,
  Clock,
  Film,
  RefreshCw,
  XCircle,
} from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';

import { Button } from '@/components/common/Button';
import { Card } from '@/components/common/Card';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { KpiCard } from '@/components/common/KpiCard';
import { ProgressBar } from '@/components/common/ProgressBar';
import { Skeleton } from '@/components/common/Skeleton';
import { StatusBadge, type BadgeStatus } from '@/components/common/StatusBadge';
import { useDashboardSummary } from '@/features/dashboard/hooks/useDashboardSummary';
import { useTasks } from '@/features/task/hooks/useTasks';
import { TASK_STATUS_LABEL } from '@/features/task/statusLabels';
import { useVideos } from '@/features/video/hooks/useVideos';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

function formatDuration(seconds: number | undefined): string {
  if (!seconds) return '-';
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return seconds >= 3600
    ? `${Math.floor(seconds / 3600)}시간 ${Math.floor((seconds % 3600) / 60)}분`
    : `${m}분 ${s}초`;
}

function NowClock() {
  const [now, setNow] = useState(() => dayjs());
  useEffect(() => {
    const t = setInterval(() => setNow(dayjs()), 1000);
    return () => clearInterval(t);
  }, []);
  return (
    <span className="text-sm text-gray-500 flex items-center gap-1">
      <Clock size={14} aria-hidden />
      {now.format('YYYY-MM-DD HH:mm:ss')}
    </span>
  );
}

/**
 * SCR-DASH-001 메인 대시보드 (mock 정합).
 *
 * 역할별 KPI:
 *   - WORKER: 4카드 (대기 / 완료 / 내 작업 / 반려)
 *   - REVIEWER: 3카드 (대기 / 완료 / 반려)
 */
export function DashboardPage() {
  const queryClient = useQueryClient();
  const role = useAuthStore((s) => s.claims?.role);
  const userIdRaw = useAuthStore((s) => s.claims?.sub);
  const userId = userIdRaw ? Number(userIdRaw) : undefined;
  const isWorker = role === Role.WORKER;

  const { data, isLoading, error } = useDashboardSummary();
  const {
    data: recentPage,
    isLoading: recentLoading,
    isError: recentError,
    refetch: refetchRecent,
  } = useVideos({
    page: 0,
    size: 5,
    sort: 'capturedAt,desc',
  });

  const { data: myTasksPage, isLoading: myTasksLoading } = useTasks(
    isWorker && userId ? { workerId: userId, size: 5 } : { size: 0 },
  );

  // 이벤트 분포 — BE 카테고리 항목(eventTypeCd=categoryKey, label, count)을 그대로 순회 렌더.
  // videoDistribution: 영상 단위 — "영상 데이터 개수" 카드용
  // imageDistribution: 프레임(이미지) 단위 — "이미지 데이터 개수" 카드용 (BE 미제공 시 영상 분포 fallback)
  const videoDistribution = data?.eventDistribution ?? [];
  const imageDistribution = data?.imageDistribution ?? data?.eventDistribution ?? [];

  const handleRefresh = () => {
    queryClient.invalidateQueries({ queryKey: ['stat'] });
    queryClient.invalidateQueries({ queryKey: ['videos'] });
    queryClient.invalidateQueries({ queryKey: ['assignments'] });
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-gray-900">대시보드</h1>
        <div className="flex items-center gap-3">
          <NowClock />
          <Button variant="secondary" size="sm" onClick={handleRefresh}>
            <RefreshCw size={14} aria-hidden />
            새로고침
          </Button>
        </div>
      </div>

      {error && <ErrorState title="대시보드 정보를 불러올 수 없습니다" />}

      {/* KPI — role 별 카드 수 */}
      <div
        data-testid="dashboard-kpi-grid"
        className={`grid grid-cols-2 gap-4 ${isWorker ? 'xl:grid-cols-4' : 'xl:grid-cols-3'}`}
      >
        {isLoading ? (
          Array.from({ length: isWorker ? 4 : 3 }).map((_, i) => (
            <Skeleton key={i} height={96} className="w-full rounded-lg" />
          ))
        ) : (
          <>
            <KpiCard
              label="처리 대기"
              value={data?.pendingCount ?? 0}
              unit="건"
              icon={<Film size={22} className="text-warning" aria-hidden />}
              iconBgClassName="bg-warning/10"
            />
            <KpiCard
              label="처리 완료"
              value={data?.completedCount ?? 0}
              unit="건"
              icon={
                <CheckCircle2
                  size={22}
                  className="text-success"
                  aria-hidden
                />
              }
              iconBgClassName="bg-success/10"
            />
            {isWorker && (
              <KpiCard
                label="내 작업"
                value={data?.myTaskCount ?? 0}
                unit="건"
                icon={
                  <ClipboardList
                    size={22}
                    className="text-primary-600"
                    aria-hidden
                  />
                }
                iconBgClassName="bg-info/10"
              />
            )}
            <KpiCard
              label="반려 건수"
              value={data?.rejectedCount ?? 0}
              unit="건"
              icon={<XCircle size={22} className="text-danger" aria-hidden />}
              iconBgClassName="bg-danger/10"
            />
          </>
        )}
      </div>

      {/* 이미지/영상 데이터 카드 (이벤트 6종 분포) */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <Card title="이미지 데이터 개수">
          {isLoading ? (
            <Skeleton height={48} />
          ) : (
            <div className="space-y-3">
              <div className="text-2xl font-semibold text-primary-600">
                {(data?.cumulativeImageCount ?? 0).toLocaleString('ko-KR')}장
              </div>
              <div className="border-t border-gray-200 pt-3">
                <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
                  {imageDistribution.map((e) => (
                    <div
                      key={e.eventTypeCd}
                      data-event-type={e.eventTypeCd}
                      className="flex justify-between text-xs"
                    >
                      <span className="text-gray-500">{e.label}</span>
                      <span className="tabular-nums font-medium text-gray-800">
                        {e.count.toLocaleString('ko-KR')}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </Card>

        <Card title="영상 데이터 개수">
          {isLoading ? (
            <Skeleton height={48} />
          ) : (
            <div className="space-y-3">
              <div className="text-2xl font-semibold text-primary-600">
                {(data?.cumulativeVideoCount ?? 0).toLocaleString('ko-KR')}건
              </div>
              <div className="border-t border-gray-200 pt-3">
                <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
                  {videoDistribution.map((e) => (
                    <div key={e.eventTypeCd} className="flex justify-between text-xs">
                      <span className="text-gray-500">{e.label}</span>
                      <span className="tabular-nums font-medium text-gray-800">
                        {e.count.toLocaleString('ko-KR')}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </Card>
      </div>

      {/* 최근 완료 영상 + (WORKER 한정) 내 작업 현황 */}
      <div className={`grid grid-cols-1 ${isWorker ? 'xl:grid-cols-2' : ''} gap-4`}>
        <Card title="최근 완료 영상">
          {recentLoading ? (
            <Skeleton height={120} />
          ) : recentError ? (
            <ErrorState
              title="목록을 불러오지 못했습니다"
              message="최근 완료 영상을 불러오는 중 오류가 발생했습니다."
              onRetry={() => refetchRecent()}
              retryLabel="재시도"
            />
          ) : (recentPage?.content ?? []).length === 0 ? (
            <p className="text-center text-gray-400 py-12 text-sm">
              완료된 영상이 없습니다.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-gray-200 bg-gray-50">
                    <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                      CCTV명
                    </th>
                    <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                      이벤트
                    </th>
                    <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                      길이
                    </th>
                    <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                      완료일
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {(recentPage?.content ?? []).map((v) => (
                    <tr
                      key={v.id}
                      className="border-b border-gray-100 hover:bg-gray-50"
                    >
                      <td className="px-4 py-3">
                        <span className="font-medium text-gray-800 text-xs">
                          {v.cctvName}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <EventTypeBadge
                          eventType={v.eventTypeCd ?? v.eventName ?? ''}
                        />
                      </td>
                      <td className="px-4 py-3">
                        <span className="text-xs">
                          {formatDuration(v.durationSec)}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className="text-xs text-gray-500">
                          {v.capturedAt
                            ? dayjs(v.capturedAt).format('MM-DD HH:mm')
                            : '-'}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        {isWorker && (
          <Card title="내 작업 현황">
            {myTasksLoading ? (
              <Skeleton height={120} />
            ) : (myTasksPage?.content ?? []).length === 0 ? (
              <p className="text-center text-gray-400 py-12 text-sm">
                작업이 없습니다.
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-200 bg-gray-50">
                      <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                        영상
                      </th>
                      <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                        상태
                      </th>
                      <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                        진행률
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {(myTasksPage?.content ?? []).map((t) => {
                      const progress =
                        t.status === 'COMPLETED'
                          ? 100
                          : t.status === 'IN_PROGRESS'
                            ? 50
                            : 0;
                      return (
                        <tr
                          key={t.id}
                          className="border-b border-gray-100 hover:bg-gray-50"
                        >
                          <td className="px-4 py-3">
                            <span className="font-medium text-gray-800 text-xs">
                              {t.cctvName}
                            </span>
                          </td>
                          <td className="px-4 py-3">
                            <StatusBadge
                              status={t.status as BadgeStatus}
                              label={TASK_STATUS_LABEL[t.status]}
                            />
                          </td>
                          <td className="px-4 py-3">
                            <div className="flex items-center gap-2 min-w-[80px]">
                              <ProgressBar
                                value={progress}
                                size="sm"
                                className="flex-1"
                              />
                              <span className="text-xs text-gray-500 tabular-nums w-8 text-right">
                                {progress}%
                              </span>
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        )}
      </div>
    </div>
  );
}
