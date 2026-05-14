import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { KpiCard } from '../KpiCard';

describe('KpiCard', () => {
  it('KpiCard_숫자_3자리_콤마_포맷', () => {
    render(<KpiCard label="누적 라벨" value={1234567} unit="건" />);
    expect(screen.getByText('1,234,567')).toBeInTheDocument();
  });

  it('KpiCard_trend_양수_success_색상', () => {
    render(<KpiCard label="이번주" value={100} trend={{ delta: 12, label: '대비' }} />);
    const trendSpan = screen.getByText(/12 대비/);
    // 양수 트렌드는 green 계열 (mock tone) — 부모 div 에 색상 클래스가 적용됨
    const trendContainer = trendSpan.parentElement;
    expect(trendContainer?.className).toMatch(/text-green-600|text-success/);
  });
});
