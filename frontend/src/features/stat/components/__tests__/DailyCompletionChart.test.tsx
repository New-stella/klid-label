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

  // 회귀 가드 — 데이터 0건이면 빈 사각형이 아니라 빈 상태 안내를 보여준다.
  //
  // 결함(브라우저 실측, `/stat/worker` 일별 작업량): recharts 가 `data=[]` 에서는 축 눈금·막대를
  // 하나도 만들지 않아 테두리만 있는 빈 사각형이 남았고(bar 0 / tick 0), 사용자에게는
  // "데이터 없음"이 아니라 "차트 고장"으로 읽혔다. 안내 문구가 어디에도 없었다.
  it('데이터가_0건이면_빈_상태_안내를_보여준다', () => {
    // given: 조회는 성공했지만 표시할 일자가 하나도 없는 경우
    // when
    render(<DailyCompletionChart data={[]} />);

    // then: 차트를 그리지 않고 안내로 대체한다
    expect(screen.queryByTestId('bar')).toBeNull();
    expect(screen.getByTestId('daily-completion-chart-empty')).toBeInTheDocument();
    // 스크린리더에도 전달돼야 한다(EmptyState 의 role="status").
    expect(screen.getByRole('status')).toHaveTextContent('표시할 작업량 데이터가 없습니다');
  });

  it('값이_전부_0이어도_데이터가_있으면_차트를_그린다', () => {
    // 값 0 과 데이터 부재는 다른 사실이다 — 전자를 빈 상태로 감추면 "이번 달 작업 0건"이라는
    // 정보가 사라진다(전체 통계 화면은 이 경우 축과 0 막대를 정상 렌더한다).
    render(<DailyCompletionChart data={[{ date: '07-01', count: 0 }]} />);

    expect(screen.getByTestId('bar')).toBeInTheDocument();
    expect(screen.queryByTestId('daily-completion-chart-empty')).toBeNull();
  });
});
