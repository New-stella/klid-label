import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

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
  color = '#6366f1',
}: SimpleBarChartProps) {
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
