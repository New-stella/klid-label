import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

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
          <Bar dataKey="count" name="완료" fill="#0F4C97" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
