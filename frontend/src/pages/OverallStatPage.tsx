import { useState } from 'react';
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { KpiCard } from '@/components/common/KpiCard';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import type { EventTypeCd } from '@/features/dashboard/types';
import { downloadReport } from '@/features/stat/api';
import { WorkerStatsTable } from '@/features/stat/components/WorkerStatsTable';
import { useOverallStat } from '@/features/stat/hooks/useOverallStat';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCR-STAT-002 전체 구축 현황 — REVIEWER 전용.
 *
 * 회귀 방지 (UI/UX §4-11):
 * - 누적 카드 영역(cumulative-cards)은 KpiCard만 사용 — ProgressBar / progressbar role / `%` 텍스트 / `<progress>` 절대 미노출
 * - 이벤트 분포는 6종 고정 슬롯 (데이터 누락 시에도 6개 `<li>` 렌더)
 * - 처리 현황 5 카드: 대기/진행중/검수 대기/승인/반려
 */

// 이벤트 6종 고정 슬롯 — 데이터 누락 시에도 6개 렌더 (UI/UX §4-3)
const FIXED_EVENT_TYPES: { code: EventTypeCd; label: string; color: string }[] = [
  { code: 'FALL', label: '낙상', color: '#a855f7' },
  { code: 'VIOLENCE', label: '폭력', color: '#ef4444' },
  { code: 'TRAFFIC_ACCIDENT', label: '교통사고', color: '#3b82f6' },
  { code: 'ABNORMAL_BEHAVIOR', label: '이상행동', color: '#f59e0b' },
  { code: 'FLOOD', label: '침수', color: '#06b6d4' },
  { code: 'WILDFIRE', label: '산불', color: '#dc2626' },
];

const IMAGE_TARGET = 100_000;
const VIDEO_TARGET = 5_000;
const DAILY_AVG_DAYS = 30;

export function OverallStatPage() {
  const { data, isLoading, error } = useOverallStat();
  const pushToast = useUiStore((s) => s.pushToast);
  const [downloading, setDownloading] = useState(false);

  const handleDownload = async () => {
    setDownloading(true);
    try {
      const blob = await downloadReport('MONTH');
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      // 보안: 파일명은 사용자 입력 미반영 — 고정 prefix + ISO 날짜
      a.download = `overall-stat-${new Date().toISOString().slice(0, 10)}.csv`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      pushToast({ variant: 'success', message: '리포트 다운로드를 시작했습니다' });
    } catch {
      pushToast({ variant: 'error', message: '리포트 다운로드에 실패했습니다' });
    } finally {
      setDownloading(false);
    }
  };

  const cumulativeImageCount = data?.cumulativeImageCount ?? 0;
  const cumulativeVideoCount = data?.cumulativeVideoCount ?? 0;
  const totalLabeled = data?.workers?.reduce((s, w) => s + w.labeled, 0) ?? 0;
  const approvedCount = data?.processing?.approved ?? 0;

  const p = data?.processing;
  const pending = p?.pending ?? 0;
  const inProgress = p?.inProgress ?? 0;
  const reviewPending = p?.reviewPending ?? 0;
  const approved = p?.approved ?? 0;
  const rejected = p?.rejected ?? 0;

  const dailyCounts = data?.dailyCounts ?? [];

  // 이벤트 분포 — 6종 고정 슬롯에 매핑
  const eventCountMap = new Map<EventTypeCd, number>();
  for (const e of data?.eventDistribution ?? []) eventCountMap.set(e.eventTypeCd, e.count);
  const eventMaxCount = Math.max(
    1,
    ...FIXED_EVENT_TYPES.map((t) => eventCountMap.get(t.code) ?? 0),
  );

  const dailyAvgImage = Math.round(cumulativeImageCount / DAILY_AVG_DAYS);
  const dailyAvgVideo = Math.round(cumulativeVideoCount / DAILY_AVG_DAYS);
  const remainImage = Math.max(IMAGE_TARGET - cumulativeImageCount, 0);
  const remainVideo = Math.max(VIDEO_TARGET - cumulativeVideoCount, 0);

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="전체 구축 현황"
        description="REVIEWER 전용 — 누적 / 처리 현황 / 이벤트 분포 / 작업자 통계"
        actions={
          <Button
            variant="primary"
            onClick={handleDownload}
            loading={downloading}
            data-testid="download-report-btn"
          >
            📥 리포트 다운로드
          </Button>
        }
      />

      {error && <ErrorState title="전체 통계를 불러올 수 없습니다" />}

      {/* 상단 4 KPI — ProgressBar 미노출 (UI/UX §4-11 회귀 방지) */}
      <div
        data-testid="cumulative-cards"
        className="grid grid-cols-2 gap-3 lg:grid-cols-4"
      >
        {isLoading ? (
          Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} height={84} className="w-full" />
          ))
        ) : (
          <>
            <KpiCard label="누적 이미지" value={cumulativeImageCount} unit="장" />
            <KpiCard label="누적 영상" value={cumulativeVideoCount} unit="건" />
            <KpiCard label="총 라벨" value={totalLabeled} unit="건" />
            <KpiCard label="검수 완료" value={approvedCount} unit="건" />
          </>
        )}
      </div>

      {/* 처리 현황 5 카드 */}
      <section aria-label="처리 현황">
        <h2 className="mb-2 text-section-title text-primary">처리 현황</h2>
        <div
          data-testid="processing-cards"
          className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5"
        >
          {isLoading ? (
            Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} height={84} className="w-full" />
            ))
          ) : (
            <>
              <KpiCard label="대기" value={pending} unit="건" />
              <KpiCard label="진행중" value={inProgress} unit="건" />
              <KpiCard label="검수 대기" value={reviewPending} unit="건" />
              <KpiCard label="승인" value={approved} unit="건" />
              <KpiCard label="반려" value={rejected} unit="건" />
            </>
          )}
        </div>
      </section>

      {/* 30일 일별 추세 — 라인 차트 */}
      <section aria-label="일별 추세">
        <h2 className="mb-2 text-section-title text-primary">30일 일별 추세</h2>
        <div className="rounded border border-border bg-white p-4">
          <div
            data-testid="daily-trend-chart"
            style={{ width: '100%', height: 240, minWidth: 240, minHeight: 160 }}
          >
            <ResponsiveContainer width="100%" height="100%" minWidth={240} minHeight={160}>
              <LineChart data={dailyCounts} margin={{ top: 8, right: 12, bottom: 8, left: 0 }}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="date" />
                <YAxis allowDecimals={false} />
                <Tooltip />
                <Line
                  type="monotone"
                  dataKey="count"
                  name="작업량"
                  stroke="#6366f1"
                  strokeWidth={2}
                  dot={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>
      </section>

      {/* 이벤트 유형별 분포 — 가로 막대 (6종 고정) */}
      <section aria-label="이벤트 분포">
        <h2 className="mb-2 text-section-title text-primary">이벤트 유형별 분포</h2>
        <ul
          data-testid="event-distribution-grid"
          aria-label="이벤트 분포"
          className="space-y-2 rounded border border-border bg-white p-4"
        >
          {FIXED_EVENT_TYPES.map((t) => {
            const count = eventCountMap.get(t.code) ?? 0;
            const widthPct = (count / eventMaxCount) * 100;
            return (
              <li key={t.code} data-event-type={t.code} className="space-y-1">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-gray-600">{t.label}</span>
                  <span className="font-medium tabular-nums">
                    {count.toLocaleString('ko-KR')}
                  </span>
                </div>
                <div className="h-2 overflow-hidden rounded-full bg-gray-100">
                  <div
                    className="h-full rounded-full"
                    style={{ width: `${widthPct}%`, backgroundColor: t.color }}
                  />
                </div>
              </li>
            );
          })}
        </ul>
      </section>

      {/* 하단 4 카드 — 일일 평균 / 잔여 작업량 */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {isLoading ? (
          Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} height={84} className="w-full" />
          ))
        ) : (
          <>
            <KpiCard label="일일 평균 (이미지)" value={dailyAvgImage} unit="건/일" />
            <KpiCard label="일일 평균 (영상)" value={dailyAvgVideo} unit="건/일" />
            <KpiCard label="잔여 작업량 (이미지)" value={remainImage} unit="건" />
            <KpiCard label="잔여 작업량 (영상)" value={remainVideo} unit="건" />
          </>
        )}
      </div>

      {/* 작업자 통계 */}
      <section aria-label="작업자 통계">
        <h2 className="mb-2 text-section-title text-primary">작업자 통계</h2>
        <WorkerStatsTable rows={data?.workers ?? []} loading={isLoading} />
      </section>
    </section>
  );
}
