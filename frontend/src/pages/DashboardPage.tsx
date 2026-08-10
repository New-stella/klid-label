import { useState } from 'react';
import dayjs from 'dayjs';
import { Clock, RefreshCw } from 'lucide-react';
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

/**
 * 표 헤더 셀 클래스 — 이 화면의 표 **2개**('최근 완료 영상' · '내 작업 현황')가 함께 쓴다.
 *
 * 글자색 하한은 `gray-600` 이다 — 헤더 배경이 secondary-50(#EEF2F7)이라 gray-500 은
 * 그 위에서 4.01:1 로 AA(4.5:1) 미달이다(gray-600 은 5.60:1).
 *
 * ⚠ 굵기는 `text-table-header` step(600)이 단독으로 정한다 — 별도 굵기 클래스를 겹치지
 * 않는다. 또 이 클래스는 반드시 **`<th>` 에 직접** 건다(`<tr>` 에만 걸면 UA 기본
 * `th { font-weight: bold }`(700)가 상속값을 이긴다).
 */
const TH_CLASS =
  'text-left text-table-header text-gray-600 uppercase tracking-wide px-4 py-3';

function formatDuration(seconds: number | undefined): string {
  if (!seconds) return '-';
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return seconds >= 3600
    ? `${Math.floor(seconds / 3600)}시간 ${Math.floor((seconds % 3600) / 60)}분`
    : `${m}분 ${s}초`;
}

/**
 * 마지막 새로고침 시각 — **정적** 텍스트다.
 *
 * ★초단위 자동 갱신 시계(구 `NowClock`)는 폐기됐다(사양 SCREEN-011). 그 시계는 1초마다
 * 리렌더하면서 "지금 이 화면이 실시간"이라는 잘못된 인상을 줬다 — 실제로 화면의 수치는
 * 새로고침 버튼을 눌러야 갱신된다. 이 텍스트는 **마지막으로 데이터를 읽은 시각**을 말한다.
 */
function LastRefreshedAt({ at }: { at: Date }) {
  return (
    <span
      data-testid="dashboard-last-refreshed"
      className="text-body-md text-gray-600 flex items-center gap-1"
    >
      <Clock size={14} aria-hidden />
      {dayjs(at).format('YYYY-MM-DD HH:mm:ss')}
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

  // 마지막으로 데이터를 읽은 시각. 최초 진입 시각으로 시작하고 새로고침할 때만 갱신된다.
  const [lastRefreshedAt, setLastRefreshedAt] = useState(() => new Date());

  const handleRefresh = () => {
    // useDashboardSummary 는 STAT_KEYS.overall()=['stats','overall'] 을 쓴다 — 하드코딩된
    // ['stat'] 은 그 어떤 쿼리와도 매칭되지 않아 새로고침 버튼이 요약 카드를 무효화하지 못했다.
    queryClient.invalidateQueries({ queryKey: STAT_KEYS.all });
    queryClient.invalidateQueries({ queryKey: VIDEO_KEYS.all });
    queryClient.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
    setLastRefreshedAt(new Date());
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-title-lg font-bold text-gray-900">대시보드</h1>
          {/* 부제 — 사양 SCREEN-011 이 제목과 함께 규정한 고정 문구다.
              이 화면이 '실시간 모니터링'이 아니라 '요약 조회'라는 성격을 먼저 말해 준다
              (수치는 새로고침을 눌러야 갱신된다 — 위 LastRefreshedAt 주석 참조). */}
          <p className="mt-0.5 text-body-md text-gray-600">
            시스템 요약 정보를 확인할 수 있습니다.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <LastRefreshedAt at={lastRefreshedAt} />
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
            {/* KPI 카드에는 장식 아이콘을 두지 않는다(2026-08-10 확정) — 라벨 문구가 이미
                무엇을 세는지 서술한다. ⚠ 구 주석은 "'대기' 는 앱 전체에서 시계 아이콘"이라고
                적혀 있었는데 그 축(StatusBadge·검수 KPI 아이콘)이 전부 폐지돼 사실과 달라졌다.
                그 문장을 근거로 아이콘을 되살리지 말 것. */}
            <KpiCard
              label="처리 대기"
              value={data?.pendingCount ?? 0}
              unit="건"
            />
            <KpiCard
              label="처리 완료"
              value={data?.completedCount ?? 0}
              unit="건"
            />
            {isWorker && (
              <KpiCard
                label="내 작업"
                value={data?.myTaskCount ?? 0}
                unit="건"
              />
            )}
            <KpiCard
              label="반려 건수"
              value={data?.rejectedCount ?? 0}
              unit="건"
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
                    {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과
                        같은 회색을 쓰면 열 구조가 먼저 읽히지 않는다. */}
                    <tr className="border-b border-gray-200 bg-secondary-50">
                      <th className={TH_CLASS}>CCTV명</th>
                      <th className={TH_CLASS}>이벤트</th>
                      <th className={TH_CLASS}>길이</th>
                      <th className={TH_CLASS}>완료일</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(recentPage?.content ?? []).map((v) => (
                      <tr
                        key={v.id}
                        className="border-b border-gray-100 transition-colors hover:bg-rowHover"
                      >
                        <td className="px-4 py-3">
                          <span className="font-medium text-gray-800 text-body-md">
                            {v.cctvName}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          <EventTypeBadge eventType={v.eventTypeCd ?? v.eventName ?? ''} />
                        </td>
                        <td className="px-4 py-3">
                          <span className="text-body-md">{formatDuration(v.durationSec)}</span>
                        </td>
                        <td className="px-4 py-3">
                          <span className="text-body-md text-gray-600">
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
                      {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과
                          같은 회색을 쓰면 열 구조가 먼저 읽히지 않는다. */}
                      <tr className="border-b border-gray-200 bg-secondary-50">
                        <th className={TH_CLASS}>영상</th>
                        <th className={TH_CLASS}>상태</th>
                        <th className={TH_CLASS}>진행률</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(myTasksPage?.content ?? []).map((t) => {
                        const progress =
                          t.status === 'COMPLETED' ? 100 : t.status === 'IN_PROGRESS' ? 50 : 0;
                        return (
                          <tr
                            key={t.id}
                            className="border-b border-gray-100 transition-colors hover:bg-rowHover"
                          >
                            <td className="px-4 py-3">
                              <span className="font-medium text-gray-800 text-body-md">
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
                                <span className="text-body-md text-gray-600 tabular-nums w-8 text-right">
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
