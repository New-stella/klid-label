import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// recharts 는 jsdom 에서 레이아웃 크기가 0 이라 SVG 를 그리지 못한다.
// 단일 시리즈 막대의 fill 값을 검증하기 위해 Bar 가 fill prop 을 노출하도록 stub 한다.
vi.mock('recharts', () => {
  const Pass = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  return {
    ResponsiveContainer: Pass,
    BarChart: Pass,
    CartesianGrid: () => null,
    XAxis: () => null,
    YAxis: () => null,
    Tooltip: () => null,
    Bar: ({ fill }: { fill?: string }) => <div data-testid="bar" data-fill={fill} />,
  };
});

import { DailyCompletionChart } from '../DailyCompletionChart';

// 진실원: LogiCraft DS-001 v6 tokens.colors.primary(KRDS 공식 토큰 CSS 그대로, 2026-08-08 교체)
const KRDS_PRIMARY = '#256EF4';
const OLD_MOCK_BLUE = '#3b82f6';

describe('DailyCompletionChart', () => {
  it('DailyCompletionChart_막대색_KRDS_primary_정본값', () => {
    // given: 일별 완료 데이터
    const data = [
      { date: '07-01', count: 3 },
      { date: '07-02', count: 5 },
    ];

    // when
    render(<DailyCompletionChart data={data} />);

    // then: 단일 시리즈 막대가 KRDS primary 색으로 칠해진다
    const bar = screen.getByTestId('bar');
    expect(bar.getAttribute('data-fill')).toBe(KRDS_PRIMARY);
    // 회귀: 옛 mock blue 가 아니다
    expect(bar.getAttribute('data-fill')).not.toBe(OLD_MOCK_BLUE);
  });
});
