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

// 색상은 카테고리 코드에 하드매핑하지 않고 순회 순서(인덱스)로 배정한다. 9 카테고리 이상도
// 모듈로 순환으로 안전하게 커버 (관제 마스터 기반 카테고리 구성은 BE 가 결정).
const PALETTE = [
  '#ef4444',
  '#f59e0b',
  '#3b82f6',
  '#10b981',
  '#8b5cf6',
  '#06b6d4',
  '#ec4899',
  '#6366f1',
  '#6b7280',
];

/** 이벤트 비율 파이차트 — BE 카테고리 항목을 순회 렌더, 색상은 인덱스 기반 팔레트 순환. */
export function EventTypePieChart({ data, height = 240 }: EventTypePieChartProps) {
  return (
    <div
      data-testid="event-type-pie-chart"
      style={{ width: '100%', height, minWidth: 200, minHeight: 160 }}
    >
      <ResponsiveContainer width="100%" height="100%" minWidth={200} minHeight={160}>
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
