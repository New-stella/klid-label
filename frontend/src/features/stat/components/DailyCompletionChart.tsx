import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

import { EmptyState } from '@/components/common/EmptyState';

export interface DailyCompletionPoint {
  date: string;
  count: number;
}

export interface DailyCompletionChartProps {
  data: DailyCompletionPoint[];
  height?: number;
}

/** 일별 완료 바차트 — 최근 N일 작업자 통계 */
export function DailyCompletionChart({ data, height = 240 }: DailyCompletionChartProps) {
  // ★ 데이터가 0건이면 차트를 그리지 않는다.
  //
  // recharts 는 `data=[]` 를 받으면 축 눈금·막대를 하나도 만들지 않아 **테두리만 있는 빈 사각형**이
  // 남는다(실측: bar 0 / tick 0 + `width(0) and height(0)` 경고). 사용자에게는 "데이터가 없다"가
  // 아니라 "차트가 고장났다"로 읽힌다. 값이 전부 0 인 것과 데이터 자체가 없는 것은 다른 사실이라,
  // 전자는 그대로 축과 0 막대를 그리고(전체 통계 화면의 동작) 후자만 빈 상태로 대체한다.
  if (data.length === 0) {
    return (
      <div data-testid="daily-completion-chart-empty" style={{ minHeight: height }}>
        <EmptyState message="표시할 작업량 데이터가 없습니다" />
      </div>
    );
  }
  return (
    <div
      data-testid="daily-completion-chart"
      style={{ width: '100%', height, minWidth: 240, minHeight: 160 }}
    >
      <ResponsiveContainer width="100%" height="100%" minWidth={240} minHeight={160}>
        <BarChart data={data} margin={{ top: 8, right: 12, bottom: 8, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="date" />
          <YAxis allowDecimals={false} />
          <Tooltip />
          <Bar dataKey="count" name="완료" fill="#256EF4" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
