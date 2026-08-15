import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { ChannelGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';

function renderRouter(initialPath: string, channel: 'INTERNAL' | 'PORTAL') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route
          path="/video/status"
          element={
            <ChannelGuard channel={channel}>
              <div>VIDEO_COMPLETED</div>
            </ChannelGuard>
          }
        />
        <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
        <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ChannelGuard', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', 'http://portal.local/login');
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('INTERNAL_채널_사용자는_INTERNAL_경로_허용', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderRouter('/video/status', 'INTERNAL');
    expect(screen.getByText('VIDEO_COMPLETED')).toBeInTheDocument();
  });

  it('PORTAL_USER가_video_completed_접근_시_ChannelGuard로_차단', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    renderRouter('/video/status', 'INTERNAL');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('claims_없으면_ingress_navigate', () => {
    renderRouter('/video/status', 'INTERNAL');
    expect(screen.getByText('INGRESS_PAGE')).toBeInTheDocument();
  });
});
