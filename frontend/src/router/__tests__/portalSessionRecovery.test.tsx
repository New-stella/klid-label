// 포털 채널 가드가 「인증이 필요합니다」로 <b>고착되지 않는다</b> — 세션이 비면 Host 창구에
// 다시 물어 되살린다.
//
// 고치는 결함(사용자 신고 「포털향에서 간헐적으로 인증이 필요합니다」): 401 한 번이면 스토어
// 세션이 비고, 포털 채널에는 그것을 다시 채우는 경로가 없어 쓰던 화면이 인증 안내로 바뀐 채
// 새로고침 전까지 돌아오지 않았다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { RoleGuard } from '@/router/guards';
import { clearPortalTokenRejection, markPortalTokenRejected } from '@/features/auth/portalSession';
import {
  clearHostTokenHandoff,
  registerHostTokenHandoff,
  type TokenHandoffGateway,
} from '@/features/auth/tokenHandoff';
import { useAuthStore } from '@/stores/useAuthStore';
import { Role } from '@/lib/api/types';

function makeToken(sub: string): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({
    sub,
    role: 'PORTAL_USER',
    channel: 'PORTAL',
    exp: Math.floor(Date.now() / 1000) + 3600,
  })}.sig`;
}

let hostToken: string | null = null;
const gateway: TokenHandoffGateway = {
  getAccessToken: () => hostToken,
  refresh: async () => hostToken,
  onUnauthorized: () => {},
  notifyActivity: () => {},
};

function renderGuarded() {
  return render(
    <MemoryRouter initialEntries={['/target']}>
      <Routes>
        <Route
          path="/target"
          element={
            <RoleGuard allow={[Role.PORTAL_USER]}>
              <div>TARGET_PAGE</div>
            </RoleGuard>
          }
        />
        <Route path="*" element={<div>CATCHALL_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('포털 채널 — 가드가 인증 안내에 고착되지 않는다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    hostToken = null;
    clearPortalTokenRejection();
    clearHostTokenHandoff();
    // [@design ADR-012] 포털 향은 토큰의 역할을 인가 축에 쓰지 않는다 — 역할의 진실원은
    // `GET /v1/me` 다. 세션이 다시 서면 스토어 구독(`sessionBootstrap`)이 안전망으로 그것을
    // 조회하므로, 이 시험이 지키는 「화면으로 돌아온다」가 성립하려면 그 조회가 응답해야 한다.
    // ⚠ 이 대역이 없으면 실패 사유가 <되맞춤 실패>가 아니라 <역할 확인 실패>가 되어, 시험이
    //   자기가 지킨다고 적은 것과 다른 것을 잡는다.
    mock = new MockAdapter(apiClient);
    mock.onGet('/me').reply(200, {
      success: true,
      data: { sub: '42', role: 'PORTAL_USER', channel: 'PORTAL', name: '포털사용자' },
      message: null,
      errorCode: null,
    });
    // 401 직후의 상태 — hydration 은 끝났고 세션만 비어 있다.
    useAuthStore.setState({ token: null, claims: null, isHydrated: true });
  });

  afterEach(() => {
    mock.restore();
    vi.unstubAllEnvs();
    clearHostTokenHandoff();
    clearPortalTokenRejection();
    useAuthStore.setState({ token: null, claims: null, isHydrated: false });
  });

  it('★세션이_비어도_Host가_토큰을_들고_있으면_화면으로_돌아온다', async () => {
    hostToken = makeToken('42');
    registerHostTokenHandoff(gateway);

    renderGuarded();

    await waitFor(() => expect(screen.getByText('TARGET_PAGE')).toBeInTheDocument());
    expect(screen.queryByTestId('portal-embed-guard-notice')).toBeNull();
    expect(screen.queryByText('CATCHALL_PAGE')).toBeNull();
    // 역할은 토큰이 아니라 서버가 채웠다 — 되맞춤이 세션을 세우고 확보가 그 뒤를 이었다.
    expect(mock.history.get.filter((r) => r.url === '/me')).toHaveLength(1);
    expect(useAuthStore.getState().claims?.role).toBe('PORTAL_USER');
  });

  it('★거부당한_토큰뿐이면_안내가_그대로_남는다_되풀이_금지', async () => {
    const dead = makeToken('42');
    hostToken = dead;
    registerHostTokenHandoff(gateway);
    markPortalTokenRejected(dead);

    renderGuarded();

    await waitFor(() =>
      expect(screen.getByTestId('portal-embed-guard-notice')).toBeInTheDocument(),
    );
    expect(screen.getByText('인증이 필요합니다')).toBeInTheDocument();
    expect(screen.queryByText('TARGET_PAGE')).toBeNull();
  });

  it('창구가_없으면_종전대로_인증_안내를_그린다', async () => {
    renderGuarded();

    await waitFor(() =>
      expect(screen.getByTestId('portal-embed-guard-notice')).toBeInTheDocument(),
    );
    expect(screen.getByText('인증이 필요합니다')).toBeInTheDocument();
  });
});
