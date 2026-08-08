import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import { EmptyState } from '@/components/common/EmptyState';

export interface SimpleBarChartProps {
  data: { label: string; value: number }[];
  height?: number;
  xAxisInterval?: number;
  color?: string;
}

export function SimpleBarChart({
  data,
  height = 200,
  xAxisInterval = 0,
  color = '#256EF4',
}: SimpleBarChartProps) {
  // 데이터 0건이면 축·막대가 하나도 안 그려져 빈 사각형만 남는다 — 작업자 통계의 일별 차트와
  // 같은 처리로 맞춘다(값이 전부 0 인 것은 데이터가 있는 것이므로 그대로 그린다).
  if (data.length === 0) {
    return (
      <div data-testid="simple-bar-chart-empty" style={{ minHeight: height }}>
        <EmptyState message="표시할 데이터가 없습니다" />
      </div>
    );
  }
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 4, right: 12, bottom: 4, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="label" interval={xAxisInterval} tick={{ fontSize: 11 }} />
        <YAxis allowDecimals={false} />
        <Tooltip />
        <Bar dataKey="value" fill={color} />
      </BarChart>
    </ResponsiveContainer>
  );
}
