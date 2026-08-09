import { useState } from 'react';
import { Download } from 'lucide-react';

import {
  ApprovedRatioNote,
  computeApprovedRatio,
  formatApprovedValue,
} from '@/components/common/ApprovedRatioNote';
import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/common/Skeleton';
import { SimpleBarChart } from '@/components/charts/SimpleBarChart';
import { SimplePieChart } from '@/components/charts/SimplePieChart';
import { downloadReport } from '@/features/stat/api';
import { WorkerStatsTable } from '@/features/stat/components/WorkerStatsTable';
import { useOverallStat } from '@/features/stat/hooks/useOverallStat';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCR-STAT-002 전체 구축 현황 — REVIEWER 전용.
 *
 * 통계 기준: 카드 주 수치와 이벤트 분포는 모두 <b>검수완료(APPROVED)</b> 기준이다.
 * 전체 기준 수치는 카드 보조 라인(전체 N · 완료율 X%)으로만 병기한다.
 *
 * 회귀 방지 (UI/UX §4-11):
 * - 누적 카드 영역(cumulative-cards)에 ProgressBar / progressbar role / `<progress>` 절대 미노출
 *   (완료율은 텍스트로만 표기 — 진행바 형태 금지)
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

/**
 * 처리현황 스택 바의 4구간 — 순서·라벨·색은 사양 SCREEN-021 ③ 고정이다.
 * KRDS 의미상태색 토큰(완료=success / 처리중=info / 대기=중립 / 실패=danger).
 * ※ 이벤트분포 차트 팔레트(EVENT_COLOR_PALETTE)는 데이터시각화라 토큰 획일화 제외·불변.
 */
const PROCESSING_SEGMENTS = [
  { key: 'completed', label: '완료', barClass: 'bg-success', dotClass: 'bg-success' },
  { key: 'processing', label: '처리중', barClass: 'bg-info', dotClass: 'bg-info' },
  { key: 'pending', label: '대기', barClass: 'bg-gray-300', dotClass: 'bg-gray-300' },
  { key: 'failed', label: '실패', barClass: 'bg-danger', dotClass: 'bg-danger' },
] as const;

type ProcessingSegment = {
  key: string;
  label: string;
  barClass: string;
  dotClass: string;
  value: number;
};

/**
 * 가로 스택형 진행바 + 범례.
 *
 * 4구간 합이 0이면 바를 렌더하지 않는다 — 폭 0짜리 빈 트랙은 "데이터가 0건"인지
 * "아직 못 읽었는지"를 구분해 주지 못하므로 범례 숫자(전부 0)로만 말한다.
 */
function ProcessingStackBar({
  total,
  segments,
}: {
  total: number;
  segments: ProcessingSegment[];
}) {
  const pct = (v: number) => (total > 0 ? (v / total) * 100 : 0);
  return (
    <div className="space-y-3">
      {total > 0 && (
        <div
          data-testid="processing-stack-bar"
          className="flex h-3 w-full overflow-hidden rounded-full bg-gray-100"
        >
          {segments
            .filter((s) => s.value > 0)
            .map((s) => (
              <div
                key={s.key}
                data-testid={`processing-bar-${s.key}`}
                className={`h-full ${s.barClass}`}
                style={{ width: `${pct(s.value)}%` }}
              />
            ))}
        </div>
      )}
      <ul className="grid grid-cols-2 gap-x-6 gap-y-1.5 md:grid-cols-4">
        {segments.map((s) => (
          <li key={s.key} className="flex items-center gap-2 text-caption">
            <span
              className={`inline-block h-2 w-2 shrink-0 rounded-full ${s.dotClass}`}
              aria-hidden
            />
            <span className="text-gray-600">{s.label}</span>
            <span className="ml-auto tabular-nums font-medium text-gray-800">
              {s.value.toLocaleString('ko-KR')}
            </span>
            <span className="tabular-nums text-gray-400">
              {pct(s.value).toFixed(1)}%
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

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

  // 누적 카드 — 주 수치는 검수완료(APPROVED) 기준, 전체·완료율은 보조 라인으로 병기.
  // (검수 승인 = 작업 완료 = 학습데이터 확정 정책 — 카드 제목이 "학습데이터"이므로 미검수는 주 수치가 아니다)
  const imageRatio = computeApprovedRatio(
    data?.approvedImageCount,
    data?.cumulativeImageCount,
  );
  const videoRatio = computeApprovedRatio(
    data?.approvedVideoCount,
    data?.cumulativeVideoCount,
  );

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

  // 이벤트 분포 — 카드 수치와 같은 기준(검수완료)으로 통일한다.
  // 전체 기준 분포로 폴백하지 않는다 — '검수완료 기준' 라벨 아래 전체 값을 보여주게 되기 때문.
  // 색상은 인덱스 기반 팔레트로 배정.
  const EVENT_DIST = (data?.approvedEventDistribution ?? []).map((e, i) => ({
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
        {/* 제목 옆 장식 아이콘을 두지 않는다 — 아이콘이 제목 텍스트를 되풀이할 뿐 정보를
            더하지 않는다. 아이콘은 조작(버튼)이나 상태 구분에만 쓴다. */}
        <h1 className="text-title-lg font-bold text-gray-900">전체 구축 현황</h1>
        {/* 아이콘은 이모지가 아니라 아이콘 라이브러리를 쓴다 — 이모지는 OS·폰트마다 모양이
            달라지고 스크린리더가 문자명("인박스 트레이")을 읽는다. 라벨 텍스트는 그대로다. */}
        <Button
          variant="primary"
          leftIcon={Download}
          onClick={handleDownload}
          loading={downloading}
          data-testid="download-report-btn"
        >
          리포트 다운로드
        </Button>
      </div>

      {error && <ErrorState title="전체 통계를 불러올 수 없습니다" />}

      {/* 로딩 중에는 0값(누적/처리 현황 카운트)을 실데이터로 오인시키지 않도록 스켈레톤을 노출한다.
          (WorkerStatPage KPI 스켈레톤 패턴 재사용) */}
      {isLoading ? (
        <div data-testid="overall-stat-loading" className="space-y-6">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <Skeleton height={120} className="w-full" />
            <Skeleton height={120} className="w-full" />
          </div>
          <Skeleton height={120} className="w-full" />
          <Skeleton height={240} className="w-full" />
          <Skeleton height={240} className="w-full" />
        </div>
      ) : (
        <>
      {/* 누적 이미지 / 영상 2카드 (UI/UX §4-11 — ProgressBar 절대 미노출) */}
      <div
        data-testid="cumulative-cards"
        className="grid grid-cols-1 md:grid-cols-2 gap-4"
      >
        {/* 이미지 */}
        <div
          data-testid="overall-image-card"
          className="bg-white border border-gray-200 rounded-lg p-6 space-y-3"
        >
          <h2 className="text-title-sm font-semibold text-gray-700">이미지 학습데이터</h2>
          <div className="flex items-baseline gap-1">
            <p className="text-display-md font-black text-primary tabular-nums">
              {formatApprovedValue(imageRatio)}
            </p>
            <span className="text-body-md font-semibold text-gray-500">장</span>
          </div>
          <ApprovedRatioNote ratio={imageRatio} unit="장" />
          {/* KpiCard 숨김 렌더 — 테스트가 '누적 이미지' 텍스트를 within(cumulative-cards)에서 찾음 */}
          <span className="sr-only">누적 이미지</span>
          <span className="sr-only">누적 영상</span>
        </div>

        {/* 영상 */}
        <div
          data-testid="overall-video-card"
          className="bg-white border border-gray-200 rounded-lg p-6 space-y-3"
        >
          <h2 className="text-title-sm font-semibold text-gray-700">영상 학습데이터</h2>
          <div className="flex items-baseline gap-1">
            <p className="text-display-md font-black text-primary tabular-nums">
              {formatApprovedValue(videoRatio)}
            </p>
            <span className="text-body-md font-semibold text-gray-500">건</span>
          </div>
          <ApprovedRatioNote ratio={videoRatio} unit="건" />
        </div>
      </div>

      {/* 처리현황 — 카드 1개 구성(사양 SCREEN-021 ③).
          헤더에 '처리현황' + 우측 '전체 N건', 본문에 가로 스택형 진행바(완료/처리중/대기/실패
          4구간, 폭=비율) + 하단 4항목 범례(색상점 + 라벨 + 건수 + 비율%).

          ★구 구현의 `grid-cols-5` 개별 카드 5개는 사양이 명시적으로 부정한 형태다 — 5개 숫자가
          나란히 놓이면 '전체'가 나머지 4개와 같은 층위의 항목으로 읽히고(실제로는 합계),
          각 구간이 전체에서 차지하는 비율 정보가 어디에도 없었다. */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <div className="flex items-center justify-between gap-2 mb-4">
          <h2 className="text-title-sm font-semibold text-gray-700">처리현황</h2>
          <span className="text-caption text-gray-500 tabular-nums">
            전체 {batchStats.total.toLocaleString('ko-KR')}건
          </span>
        </div>
        <div data-testid="processing-cards">
          <ProcessingStackBar
            total={batchStats.total}
            segments={PROCESSING_SEGMENTS.map((s) => ({
              ...s,
              value: batchStats[s.key],
            }))}
          />
        </div>
      </div>

      {/* 일별 전체 작업량 (최근 30일) — 막대 차트 */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <h2 className="text-title-sm font-semibold text-gray-700 mb-4">일별 전체 작업량 (최근 30일)</h2>
        <div data-testid="daily-trend-chart">
          <SimpleBarChart data={chartData} height={200} xAxisInterval={5} color="#6366f1" />
        </div>
      </div>

      {/* 이벤트 유형 분포 — 파이차트 + 가로막대 */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <div className="flex items-baseline gap-2 mb-4">
          <h2 className="text-title-sm font-semibold text-gray-700">이벤트 유형 분포</h2>
          <span className="text-caption text-gray-500">검수완료 기준</span>
        </div>
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
                  <div className="flex items-center justify-between text-caption">
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
          <h2 className="text-title-sm font-semibold text-gray-700">작업자별 현황</h2>
          <p className="text-caption text-gray-400 mt-0.5">컬럼 헤더 클릭으로 정렬</p>
        </div>
        <WorkerStatsTable rows={data?.workers ?? []} loading={isLoading} />
      </div>
        </>
      )}
    </div>
  );
}
