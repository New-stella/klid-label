import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { SideBySideCompare } from '../components/SideBySideCompare';

describe('SideBySideCompare (V1.6 — 50:50)', () => {
  it('좌우_50_50_이미지_렌더링', () => {
    render(
      <SideBySideCompare
        leftImage="/o.jpg"
        rightImage="/d.jpg"
        leftLabel="원본"
        rightLabel="비식별"
      />,
    );
    expect(screen.getByTestId('side-by-side-left')).toBeInTheDocument();
    expect(screen.getByTestId('side-by-side-right')).toBeInTheDocument();
    // 슬라이더 등 오버레이 X (V1.6)
    expect(screen.queryByRole('slider')).not.toBeInTheDocument();
  });

  it('우측_이미지_누락시_placeholder_표시', () => {
    render(
      <SideBySideCompare
        leftImage="/o.jpg"
        rightImage=""
        leftLabel="원본"
        rightLabel="비식별"
        rightMissingText="비식별 이미지 없음"
      />,
    );
    expect(screen.getByTestId('side-by-side-right-missing')).toHaveTextContent(
      '비식별 이미지 없음',
    );
    expect(screen.queryByTestId('side-by-side-right')).not.toBeInTheDocument();
  });
});
