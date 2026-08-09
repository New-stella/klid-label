import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// recharts 는 jsdom 에서 레이아웃 크기가 0 이라 SVG 를 그리지 못한다.
// 막대 fill 값을 검증하기 위해 Bar 가 fill prop 을 노출하도록 stub 한다.
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

import { SimpleBarChart } from '../SimpleBarChart';

// 진실원: LogiCraft DS-001 v6 tokens.colors.primary(KRDS 공식 토큰 CSS 그대로, 2026-08-08 교체)
const KRDS_PRIMARY = '#256EF4';
const OLD_MOCK_INDIGO = '#6366f1';

const data = [
  { label: 'A', value: 4 },
  { label: 'B', value: 7 },
];

describe('SimpleBarChart', () => {
  it('SimpleBarChart_기본색_KRDS_primary_정본값', () => {
    // given/when: color prop 미지정 → 기본색 사용
    render(<SimpleBarChart data={data} />);

    // then: 기본 막대색이 KRDS primary
    const bar = screen.getByTestId('bar');
    expect(bar.getAttribute('data-fill')).toBe(KRDS_PRIMARY);
    // 회귀: 옛 mock indigo 가 기본색이 아니다
    expect(bar.getAttribute('data-fill')).not.toBe(OLD_MOCK_INDIGO);
  });

  it('SimpleBarChart_color_prop_명시시_해당색_존중', () => {
    // given: 호출부가 명시적으로 색을 넘김
    const explicit = '#117C44';

    // when
    render(<SimpleBarChart data={data} color={explicit} />);

    // then: 기본색이 아니라 명시색을 사용
    expect(screen.getByTestId('bar').getAttribute('data-fill')).toBe(explicit);
  });

  // 작업자 통계의 일별 차트와 **같은 처리**로 맞춘다 — 한쪽만 빈 상태를 가지면 같은 종류의
  // 차트가 화면마다 다르게 고장 나 보인다(전체 통계는 데이터가 늘 있어 드러나지 않았을 뿐이다).
  it('데이터가_0건이면_빈_상태_안내를_보여준다', () => {
    render(<SimpleBarChart data={[]} />);

    expect(screen.queryByTestId('bar')).toBeNull();
    expect(screen.getByRole('status')).toHaveTextContent('표시할 데이터가 없습니다');
  });

  it('값이_전부_0이어도_데이터가_있으면_차트를_그린다', () => {
    render(<SimpleBarChart data={[{ label: 'A', value: 0 }]} />);

    expect(screen.getByTestId('bar')).toBeInTheDocument();
    expect(screen.queryByTestId('simple-bar-chart-empty')).toBeNull();
  });
});
