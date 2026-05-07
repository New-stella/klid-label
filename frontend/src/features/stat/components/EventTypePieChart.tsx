import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';

import type { EventTypeCd } from '@/features/dashboard/types';

export interface EventTypePoint {
  eventTypeCd: EventTypeCd;
  label: string;
  count: number;
}

export interface EventTypePieChartProps {
  data: EventTypePoint[];
  height?: number;
}

const PALETTE = ['#ef4444', '#f59e0b', '#3b82f6', '#10b981', '#8b5cf6', '#6b7280'];

/** 이벤트 비율 파이차트 — 6종 고정 */
export function EventTypePieChart({ data, height = 240 }: EventTypePieChartProps) {
  return (
    <div data-testid="event-type-pie-chart" style={{ width: '100%', height }}>
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie
            data={data}
            dataKey="count"
            nameKey="label"
            cx="50%"
            cy="50%"
            outerRadius={80}
            label
          >
            {data.map((_, idx) => (
              <Cell key={idx} fill={PALETTE[idx % PALETTE.length]} />
            ))}
          </Pie>
          <Tooltip />
          <Legend />
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}
