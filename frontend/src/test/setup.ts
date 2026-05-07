import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

afterEach(() => {
  cleanup();
});

// recharts ResponsiveContainer는 jsdom에서 width/height=0이라 차트가 비어 렌더된다.
// 테스트에서는 고정 크기로 감싸서 자식 차트 SVG가 정상 마운트되도록 한다.
vi.mock('recharts', async () => {
  const actual = await vi.importActual<typeof import('recharts')>('recharts');
  const React = await vi.importActual<typeof import('react')>('react');
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) =>
      React.createElement(
        'div',
        { 'data-testid': 'responsive-container', style: { width: 600, height: 240 } },
        children,
      ),
  };
});
