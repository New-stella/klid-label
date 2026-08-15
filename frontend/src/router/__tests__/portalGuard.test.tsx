import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { ChannelGuard, RoleGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';
import { Role } from '@/lib/api/types';

function PortalGuard({ children }: { children: React.ReactNode }) {
  return (
    <ChannelGuard channel="PORTAL">
      <RoleGuard allow={[Role.PORTAL_USER]}>{children}</RoleGuard>
    </ChannelGuard>
  );
}

function InternalGuard({ children }: { children: React.ReactNode }) {
  return (
    <ChannelGuard channel="INTERNAL">
      <RoleGuard allow={[Role.REVIEWER, Role.WORKER]}>{children}</RoleGuard>
    </ChannelGuard>
  );
}

function renderApp(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route
          path="/portal"
          element={
            <PortalGuard>
              <div>PORTAL_HOME_PAGE</div>
            </PortalGuard>
          }
        />
        <Route
          path="/portal/label/:id"
          element={
            <PortalGuard>
              <div>PORTAL_LABEL_PAGE</div>
            </PortalGuard>
          }
        />
        <Route
          path="/video/status"
          element={
            <InternalGuard>
              <div>INTERNAL_VIDEO_PAGE</div>
            </InternalGuard>
          }
        />
        <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
        <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('포털 채널 가드', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    useAuthStore.getState().clear();
  });

  it('PORTAL_USER로_video_completed_접근시_forbidden', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });

    renderApp('/video/status');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('INTERNAL_사용자가_portal_접근시_forbidden', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });

    const { unmount } = renderApp('/portal');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
    unmount();

    renderApp('/portal/label/123');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('REVIEWER도_portal_접근시_forbidden', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });

    renderApp('/portal');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('PORTAL_USER로_portal_접근시_허용', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });

    renderApp('/portal');
    expect(screen.getByText('PORTAL_HOME_PAGE')).toBeInTheDocument();
  });

  it('PORTAL_USER로_portal_label_접근시_허용', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });

    renderApp('/portal/label/42');
    expect(screen.getByText('PORTAL_LABEL_PAGE')).toBeInTheDocument();
  });
});
