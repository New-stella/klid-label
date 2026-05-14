import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

import { useAuthStore } from '@/stores/useAuthStore';

// 테스트 환경에서는 zustand persist hydration 이 즉시 끝난 상태로 가정한다.
// 가드 컴포넌트들이 `isHydrated=false` 일 때 "인증 확인 중" Spinner 만 렌더하기 때문에
// 모든 테스트 시작 시점에 hydration 완료 플래그를 강제 셋팅한다.
// 각 테스트의 `clear()` 호출은 token/claims 만 비우고 isHydrated 는 유지하므로 안전하다.
beforeEach(() => {
  useAuthStore.setState({ isHydrated: true });
});

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
