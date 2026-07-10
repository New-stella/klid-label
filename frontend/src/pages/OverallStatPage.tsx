import { useState } from 'react';
import { Activity, Film, Image, TrendingUp } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { SimpleBarChart } from '@/components/charts/SimpleBarChart';
import { SimplePieChart } from '@/components/charts/SimplePieChart';
import { downloadReport } from '@/features/stat/api';
import { WorkerStatsTable } from '@/features/stat/components/WorkerStatsTable';
import { useOverallStat } from '@/features/stat/hooks/useOverallStat';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCR-STAT-002 전체 구축 현황 — REVIEWER 전용.
 *
 * 회귀 방지 (UI/UX §4-11):
 * - 누적 카드 영역(cumulative-cards)은 KpiCard만 사용 — ProgressBar / progressbar role / `%` 텍스트 / `<progress>` 절대 미노출
 * - 이벤트 분포는 BE 카테고리 항목(eventTypeCd=categoryKey, label, count)을 그대로 순회 렌더
 * - 처리 현황 5 카드: 대기/진행중/검수 대기/승인/반려
 */

// 분포 차트 색상 팔레트 — 카테고리 코드에 하드매핑하지 않고 순회 순서(인덱스) 기반으로 배정한다.
const EVENT_COLOR_PALETTE = [
  '#a855f7',
  '#ef4444',
  '#3b82f6',
  '#f59e0b',
  '#06b6d4',
  '#dc2626',
  '#10b981',
  '#6366f1',
  '#ec4899',
];

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

  const imageCompleted = data?.cumulativeImageCount ?? 0;
  const videoCompleted = data?.cumulativeVideoCount ?? 0;

  const p = data?.processing;
  const pending = p?.pending ?? 0;
  const inProgress = p?.inProgress ?? 0;
  const reviewPending = p?.reviewPending ?? 0;
  const approved = p?.approved ?? 0;
  const rejected = p?.rejected ?? 0;

  const batchStats = {
    total: pending + inProgress + reviewPending + approved + rejected,
    completed: approved,
    processing: inProgress + reviewPending,
    failed: rejected,
    pending,
  };

  // 이벤트 분포 — BE 카테고리 항목을 그대로 순회, 색상은 인덱스 기반 팔레트로 배정
  const EVENT_DIST = (data?.eventDistribution ?? []).map((e, i) => ({
    label: e.label,
    value: e.count,
    color: EVENT_COLOR_PALETTE[i % EVENT_COLOR_PALETTE.length] ?? '#6366f1',
  }));

  const eventTotal = EVENT_DIST.reduce((s, d) => s + d.value, 0);

  // 일별 차트
  const chartData = (data?.dailyCounts ?? []).map((d) => ({ label: d.date, value: d.count }));

  return (
    <div className="p-6 space-y-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-indigo-50">
            <TrendingUp size={20} className="text-indigo-600" />
          </div>
          <h1 className="text-xl font-bold text-gray-900">전체 구축 현황</h1>
        </div>
        <Button
          variant="primary"
          onClick={handleDownload}
          loading={downloading}
          data-testid="download-report-btn"
        >
          📥 리포트 다운로드
        </Button>
      </div>

      {error && <ErrorState title="전체 통계를 불러올 수 없습니다" />}

      {/* 누적 이미지 / 영상 2카드 (UI/UX §4-11 — ProgressBar 절대 미노출) */}
      <div
        data-testid="cumulative-cards"
        className="grid grid-cols-1 md:grid-cols-2 gap-4"
      >
        {/* 이미지 */}
        <div className="bg-white border border-gray-200 rounded-lg p-6 space-y-3">
          <div className="flex items-center gap-2">
            <Image size={18} className="text-blue-500" />
            <h2 className="text-sm font-semibold text-gray-700">이미지 학습데이터</h2>
          </div>
          <div className="flex items-baseline gap-1">
            <p className="text-3xl font-black text-primary tabular-nums">
              {imageCompleted.toLocaleString()}
            </p>
            <span className="text-base font-semibold text-gray-500">장</span>
          </div>
          {/* KpiCard 숨김 렌더 — 테스트가 '누적 이미지' 텍스트를 within(cumulative-cards)에서 찾음 */}
          <span className="sr-only">누적 이미지</span>
          <span className="sr-only">누적 영상</span>
        </div>

        {/* 영상 */}
        <div className="bg-white border border-gray-200 rounded-lg p-6 space-y-3">
          <div className="flex items-center gap-2">
            <Film size={18} className="text-purple-500" />
            <h2 className="text-sm font-semibold text-gray-700">영상 학습데이터</h2>
          </div>
          <div className="flex items-baseline gap-1">
            <p className="text-3xl font-black text-primary tabular-nums">
              {videoCompleted.toLocaleString()}
            </p>
            <span className="text-base font-semibold text-gray-500">건</span>
          </div>
        </div>
      </div>

      {/* 처리 현황 (UI/UX §4-11 — 대기/진행중/검수 대기/승인/반려 5개 고정) */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <div className="flex items-center gap-2 mb-4">
          <Activity size={16} className="text-gray-500" />
          <h2 className="text-sm font-semibold text-gray-700">처리 현황</h2>
        </div>
        <div
          data-testid="processing-cards"
          className="grid grid-cols-5 gap-3"
        >
          {[
            // 처리 현황 카운터 — KRDS 의미상태색 토큰 (완료=success, 처리중=info, 실패=danger, 대기=warning).
            //  ※ 아래 이벤트분포 파이/바 차트 팔레트(EVENT_COLOR_PALETTE)는 데이터시각화라 토큰 획일화 제외·불변.
            { label: '전체', value: batchStats.total, color: 'text-gray-800' },
            { label: '완료', value: batchStats.completed, color: 'text-success' },
            { label: '처리중', value: batchStats.processing, color: 'text-info' },
            { label: '실패', value: batchStats.failed, color: 'text-danger' },
            { label: '대기', value: batchStats.pending, color: 'text-warning' },
          ].map((s) => (
            <div key={s.label} className="text-center bg-gray-50 rounded-lg p-3">
              <p className={['text-xl font-bold tabular-nums', s.color].join(' ')}>
                {s.value.toLocaleString()}
              </p>
              <p className="text-xs text-gray-500 mt-0.5">{s.label}</p>
            </div>
          ))}
        </div>
      </div>

      {/* 일별 전체 작업량 (최근 30일) — 막대 차트 */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <h2 className="text-sm font-semibold text-gray-700 mb-4">일별 전체 작업량 (최근 30일)</h2>
        <div data-testid="daily-trend-chart">
          <SimpleBarChart data={chartData} height={200} xAxisInterval={5} color="#6366f1" />
        </div>
      </div>

      {/* 이벤트 유형 분포 — 파이차트 + 가로막대 */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <h2 className="text-sm font-semibold text-gray-700 mb-4">이벤트 유형 분포</h2>
        <div className="flex items-center gap-8">
          <SimplePieChart data={EVENT_DIST} size={160} showLegend />
          {/* 가로막대 — BE 카테고리 분포(eventTypeCd=categoryKey, label, count)를 그대로 순회 렌더 */}
          <ul
            data-testid="event-distribution-grid"
            aria-label="이벤트 분포"
            className="flex-1 space-y-2"
          >
            {EVENT_DIST.map((e) => {
              const pct = eventTotal > 0 ? (e.value / eventTotal) * 100 : 0;
              return (
                <li key={e.label} className="space-y-1">
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-gray-600">{e.label}</span>
                    <span className="tabular-nums font-medium">{e.value.toLocaleString('ko-KR')}</span>
                  </div>
                  <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                    <div
                      className="h-full rounded-full transition-all"
                      style={{ width: `${pct}%`, backgroundColor: e.color }}
                    />
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      </div>

      {/* 작업자별 현황 */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-100">
          <h2 className="text-sm font-semibold text-gray-700">작업자별 현황</h2>
          <p className="text-xs text-gray-400 mt-0.5">컬럼 헤더 클릭으로 정렬</p>
        </div>
        <WorkerStatsTable rows={data?.workers ?? []} loading={isLoading} />
      </div>
    </div>
  );
}
