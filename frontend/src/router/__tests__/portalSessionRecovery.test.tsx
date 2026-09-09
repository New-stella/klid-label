// 포털 채널 가드가 「인증이 필요합니다」로 <b>고착되지 않는다</b> — 세션이 비면 Host 창구에
// 다시 물어 되살린다.
//
// 고치는 결함(사용자 신고 「포털향에서 간헐적으로 인증이 필요합니다」): 401 한 번이면 스토어
// 세션이 비고, 포털 채널에는 그것을 다시 채우는 경로가 없어 쓰던 화면이 인증 안내로 바뀐 채
// 새로고침 전까지 돌아오지 않았다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { RoleGuard } from '@/router/guards';
import {
  clearPortalTokenRejection,
  markPortalTokenRejected,
} from '@/features/auth/portalSession';
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
  beforeEach(() => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    hostToken = null;
    clearPortalTokenRejection();
    clearHostTokenHandoff();
    // 401 직후의 상태 — hydration 은 끝났고 세션만 비어 있다.
    useAuthStore.setState({ token: null, claims: null, isHydrated: true });
  });

  afterEach(() => {
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
