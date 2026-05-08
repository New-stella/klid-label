import { useEffect, useState } from 'react';
import dayjs from 'dayjs';
import {
  CheckCircle2,
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
import { Skeleton } from '@/components/common/Skeleton';
import { useDashboardSummary } from '@/features/dashboard/hooks/useDashboardSummary';
import type { EventTypeCd } from '@/features/dashboard/types';
import { useVideos } from '@/features/video/hooks/useVideos';

// mock 정합 — 6종 이벤트 고정 라벨 매핑 (BE EventTypeCd → 한글)
const EVENT_LABELS: { code: EventTypeCd; label: string }[] = [
  { code: 'FALL', label: '쓰러짐' },
  { code: 'VIOLENCE', label: '폭력' },
  { code: 'TRAFFIC_ACCIDENT', label: '교통사고' },
  { code: 'ABNORMAL_BEHAVIOR', label: '이상행동(유괴)' },
  { code: 'FLOOD', label: '침수' },
  { code: 'WILDFIRE', label: '산불' },
];

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
 * 레이아웃:
 *   - 헤더: 제목 + 우측(시계 + 새로고침)
 *   - KPI 3카드 (처리 대기 / 처리 완료 / 반려 건수)
 *   - 이미지 데이터 / 영상 데이터 카드 (이벤트 6종 분포 그리드)
 *   - 최근 완료 영상 테이블
 */
export function DashboardPage() {
  const queryClient = useQueryClient();
  const { data, isLoading, error } = useDashboardSummary();
  const { data: recentPage, isLoading: recentLoading } = useVideos({
    page: 0,
    size: 5,
    sort: 'capturedAt,desc',
  });

  // 이벤트 분포를 코드 → count 맵으로
  const distMap = new Map<string, number>();
  for (const d of data?.eventDistribution ?? []) {
    distMap.set(d.eventTypeCd, d.count);
  }

  const handleRefresh = () => {
    queryClient.invalidateQueries({ queryKey: ['stat'] });
    queryClient.invalidateQueries({ queryKey: ['videos'] });
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-gray-900">대시보드</h1>
        <div className="flex items-center gap-3">
          <NowClock />
          <Button variant="outline" size="sm" onClick={handleRefresh}>
            <RefreshCw size={14} aria-hidden />
            새로고침
          </Button>
        </div>
      </div>

      {error && <ErrorState title="대시보드 정보를 불러올 수 없습니다" />}

      {/* KPI 3카드 (처리 대기 / 처리 완료 / 반려 건수) */}
      <div
        data-testid="dashboard-kpi-grid"
        className="grid grid-cols-2 gap-4 xl:grid-cols-3"
      >
        {isLoading ? (
          Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} height={96} className="w-full rounded-lg" />
          ))
        ) : (
          <>
            <KpiCard
              label="처리 대기"
              value={data?.pendingCount ?? 0}
              unit="건"
              icon={<Film size={22} className="text-yellow-600" aria-hidden />}
            />
            <KpiCard
              label="처리 완료"
              value={data?.completedCount ?? 0}
              unit="건"
              icon={<CheckCircle2 size={22} className="text-green-600" aria-hidden />}
            />
            <KpiCard
              label="반려 건수"
              value={data?.rejectedCount ?? 0}
              unit="건"
              icon={<XCircle size={22} className="text-red-600" aria-hidden />}
            />
          </>
        )}
      </div>

      {/* 이미지/영상 데이터 카드 (이벤트 6종 분포) */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
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
                  {EVENT_LABELS.map((e) => (
                    <div
                      key={e.code}
                      data-event-type={e.code}
                      className="flex justify-between text-xs"
                    >
                      <span className="text-gray-500">{e.label}</span>
                      <span className="tabular-nums font-medium text-gray-800">
                        {(distMap.get(e.code) ?? 0).toLocaleString('ko-KR')}
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
                  {EVENT_LABELS.map((e) => (
                    <div key={e.code} className="flex justify-between text-xs">
                      <span className="text-gray-500">{e.label}</span>
                      <span className="tabular-nums font-medium text-gray-800">
                        {(distMap.get(e.code) ?? 0).toLocaleString('ko-KR')}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </Card>
      </div>

      {/* 최근 완료 영상 */}
      <Card title="최근 완료 영상">
        {recentLoading ? (
          <Skeleton height={120} />
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
                    프레임수
                  </th>
                  <th className="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide px-4 py-3">
                    완료일
                  </th>
                </tr>
              </thead>
              <tbody>
                {(recentPage?.content ?? []).map((v) => (
                  <tr key={v.id} className="border-b border-gray-100 hover:bg-gray-50">
                    <td className="px-4 py-3">
                      <span className="font-medium text-gray-800 text-xs">{v.cctvName}</span>
                    </td>
                    <td className="px-4 py-3">
                      <EventTypeBadge eventType={v.eventTypeCd ?? v.eventName ?? ''} />
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs">{(v.frameCount ?? 0).toLocaleString('ko-KR')}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs text-gray-500">
                        {v.capturedAt ? dayjs(v.capturedAt).format('MM-DD HH:mm') : '-'}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
