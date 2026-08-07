import { useEffect, useState } from 'react';
import dayjs from 'dayjs';
import { CheckCircle2, ClipboardList, Clock, Film, RefreshCw, XCircle } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';

import {
  ApprovedRatioNote,
  computeApprovedRatio,
  formatApprovedValue,
} from '@/components/common/ApprovedRatioNote';
import { Button } from '@/components/common/Button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/common/Card';
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
import { ASSIGNMENT_KEYS, STAT_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';
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
    <span className="text-body-md text-gray-500 flex items-center gap-1">
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
    // "최근 완료" 는 적재 순서(capturedAt=regDt)가 아니라 검수 완료 순서다 —
    // BE SortAllowlist.VIDEO_WITH_REVIEW_STATUS 가 reviewStatusCd 지정 호출에서만 이 키를 해석한다
    // (LS_RAW_DATA_STATUS 조인 쿼리에서만 유효한 정렬 축이라 아래 필터와 짝을 이룬다).
    sort: 'reviewCompletedAt,desc',
    // "최근 완료 영상" — 검수 승인(APPROVED) = 작업 완료 정책이므로 승인된 영상만 노출한다.
    // 필터가 없으면 미검수 영상까지 섞여 위 '영상 데이터 개수'(검수완료 기준) 카드와 모순된다.
    reviewStatusCd: 'APPROVED',
  });

  const { data: myTasksPage, isLoading: myTasksLoading } = useTasks(
    isWorker && userId ? { workerId: userId, size: 5 } : { size: 0 },
  );

  // 누적 카드 — 주 수치는 검수완료(APPROVED) 기준, 전체·완료율은 보조 라인으로 병기.
  // (검수 승인 = 작업 완료 = 학습데이터 확정 정책)
  const imageRatio = computeApprovedRatio(data?.approvedImageCount, data?.cumulativeImageCount);
  const videoRatio = computeApprovedRatio(data?.approvedVideoCount, data?.cumulativeVideoCount);

  // 이벤트 분포 — 카드 수치와 같은 기준(검수완료)으로 통일한다.
  // 전체 기준 분포로 폴백하지 않는다 — '검수완료 기준' 라벨 아래 전체 값을 보여주게 되기 때문.
  // videoDistribution: 영상 단위 — "영상 데이터 개수" 카드용
  // imageDistribution: 프레임(이미지) 단위 — "이미지 데이터 개수" 카드용
  const videoDistribution = data?.approvedEventDistribution ?? [];
  const imageDistribution = data?.approvedImageDistribution ?? [];

  const handleRefresh = () => {
    // useDashboardSummary 는 STAT_KEYS.overall()=['stats','overall'] 을 쓴다 — 하드코딩된
    // ['stat'] 은 그 어떤 쿼리와도 매칭되지 않아 새로고침 버튼이 요약 카드를 무효화하지 못했다.
    queryClient.invalidateQueries({ queryKey: STAT_KEYS.all });
    queryClient.invalidateQueries({ queryKey: VIDEO_KEYS.all });
    queryClient.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-title-lg font-bold text-gray-900">대시보드</h1>
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
              icon={<CheckCircle2 size={22} className="text-success" aria-hidden />}
              iconBgClassName="bg-success/10"
            />
            {isWorker && (
              <KpiCard
                label="내 작업"
                value={data?.myTaskCount ?? 0}
                unit="건"
                icon={<ClipboardList size={22} className="text-primary-600" aria-hidden />}
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
        <Card data-testid="dashboard-image-card">
          <CardHeader>
            <CardTitle>이미지 데이터 개수</CardTitle>
          </CardHeader>
          <CardContent>
            {isLoading ? (
              <Skeleton height={48} />
            ) : (
              <div className="space-y-3">
                <div className="flex items-baseline gap-1">
                  <span className="text-display-sm font-semibold text-primary-600 tabular-nums">
                    {formatApprovedValue(imageRatio)}
                  </span>
                  <span className="text-body-md font-semibold text-gray-500">장</span>
                </div>
                <ApprovedRatioNote ratio={imageRatio} unit="장" />
                <div className="border-t border-gray-200 pt-3">
                  <p className="mb-2 text-label font-medium text-gray-500">
                    이벤트 분포 (검수완료 기준)
                  </p>
                  <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
                    {imageDistribution.map((e) => (
                      <div
                        key={e.eventTypeCd}
                        data-event-type={e.eventTypeCd}
                        className="flex justify-between text-caption"
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
          </CardContent>
        </Card>

        <Card data-testid="dashboard-video-card">
          <CardHeader>
            <CardTitle>영상 데이터 개수</CardTitle>
          </CardHeader>
          <CardContent>
            {isLoading ? (
              <Skeleton height={48} />
            ) : (
              <div className="space-y-3">
                <div className="flex items-baseline gap-1">
                  <span className="text-display-sm font-semibold text-primary-600 tabular-nums">
                    {formatApprovedValue(videoRatio)}
                  </span>
                  <span className="text-body-md font-semibold text-gray-500">건</span>
                </div>
                <ApprovedRatioNote ratio={videoRatio} unit="건" />
                <div className="border-t border-gray-200 pt-3">
                  <p className="mb-2 text-label font-medium text-gray-500">
                    이벤트 분포 (검수완료 기준)
                  </p>
                  <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
                    {videoDistribution.map((e) => (
                      <div key={e.eventTypeCd} className="flex justify-between text-caption">
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
          </CardContent>
        </Card>
      </div>

      {/* 최근 완료 영상 + (WORKER 한정) 내 작업 현황 */}
      <div className={`grid grid-cols-1 ${isWorker ? 'xl:grid-cols-2' : ''} gap-4`}>
        <Card>
          <CardHeader>
            <CardTitle>최근 완료 영상</CardTitle>
          </CardHeader>
          <CardContent>
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
              <p className="text-center text-gray-400 py-12 text-body-md">
                완료된 영상이 없습니다.
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-body-md">
                  <thead>
                    <tr className="border-b border-gray-200 bg-gray-50">
                      <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                        CCTV명
                      </th>
                      <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                        이벤트
                      </th>
                      <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                        길이
                      </th>
                      <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                        완료일
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {(recentPage?.content ?? []).map((v) => (
                      <tr key={v.id} className="border-b border-gray-100 hover:bg-gray-50">
                        <td className="px-4 py-3">
                          <span className="font-medium text-gray-800 text-label">{v.cctvName}</span>
                        </td>
                        <td className="px-4 py-3">
                          <EventTypeBadge eventType={v.eventTypeCd ?? v.eventName ?? ''} />
                        </td>
                        <td className="px-4 py-3">
                          <span className="text-caption">{formatDuration(v.durationSec)}</span>
                        </td>
                        <td className="px-4 py-3">
                          <span className="text-caption text-gray-500">
                            {/* 완료일 = 검수 완료 시각(BE reviewCompletedAt = LS_RAW_DATA_STATUS.UPD_DT).
                              적재 시각(capturedAt)으로 폴백하지 않는다 — 폴백하면 '완료일' 컬럼에
                              완료와 무관한 값이 실려 정렬 축(reviewCompletedAt)과도 어긋난다. */}
                            {v.reviewCompletedAt
                              ? dayjs(v.reviewCompletedAt).format('MM-DD HH:mm')
                              : '-'}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>

        {isWorker && (
          <Card>
            <CardHeader>
              <CardTitle>내 작업 현황</CardTitle>
            </CardHeader>
            <CardContent>
              {myTasksLoading ? (
                <Skeleton height={120} />
              ) : (myTasksPage?.content ?? []).length === 0 ? (
                <p className="text-center text-gray-400 py-12 text-body-md">작업이 없습니다.</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-body-md">
                    <thead>
                      <tr className="border-b border-gray-200 bg-gray-50">
                        <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                          영상
                        </th>
                        <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                          상태
                        </th>
                        <th className="text-left text-table-header font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                          진행률
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {(myTasksPage?.content ?? []).map((t) => {
                        const progress =
                          t.status === 'COMPLETED' ? 100 : t.status === 'IN_PROGRESS' ? 50 : 0;
                        return (
                          <tr key={t.id} className="border-b border-gray-100 hover:bg-gray-50">
                            <td className="px-4 py-3">
                              <span className="font-medium text-gray-800 text-label">
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
                                <ProgressBar value={progress} size="sm" className="flex-1" />
                                <span className="text-caption text-gray-500 tabular-nums w-8 text-right">
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
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  );
}
