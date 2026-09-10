import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { App } from '@/App';

/**
 * [@design ADR-012] [@design SHELL-001] [@design AC-1105] [@design AC-1106]
 * 세션 만료 감시·연장 팝업의 <b>탑재 자리</b> 가드 — 앱 최상단 · 관제 채널 빌드만.
 *
 * 감시 자체의 동작은 `features/auth/__tests__/ControlSessionMonitor.test.tsx` 가 덮는다. 여기서 지키는
 * 것은 ①셸이 아니라 앱 최상단에 물려 있어 셸 밖 전체 화면(라벨링 캔버스)에서도 살아 있는가
 * ②포털 채널 빌드에서는 물리지 않는가 둘이다. 감시만 검증하면 「App 이 그것을 탑재하지 않게」 또는
 * 「포털에도 탑재하게」 바꾸는 변이가 살아남는다.
 *
 * 라우터는 대역이다 — 셸(`AppLayout`) 밖 경로 하나만 둔다.
 */
vi.mock('@/router', async () => {
  const { createMemoryRouter } = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return {
    router: createMemoryRouter([{ path: '/', element: <div>FULLSCREEN_OUTSIDE_SHELL</div> }]),
  };
});

vi.mock('@/features/auth/ControlSessionMonitor', () => ({
  ControlSessionMonitor: () => <div data-testid="control-session-monitor" />,
}));

describe('App — 관제 채널 세션 감시 탑재', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('★관제_채널_빌드는_셸_밖_화면에서도_감시를_탑재한다', async () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
    render(<App />);
    expect(await screen.findByText('FULLSCREEN_OUTSIDE_SHELL')).toBeInTheDocument();
    expect(screen.getByTestId('control-session-monitor')).toBeInTheDocument();
  });

  it('★포털_채널_빌드는_감시를_탑재하지_않는다', async () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    render(<App />);
    expect(await screen.findByText('FULLSCREEN_OUTSIDE_SHELL')).toBeInTheDocument();
    expect(screen.queryByTestId('control-session-monitor')).toBeNull();
  });
});
