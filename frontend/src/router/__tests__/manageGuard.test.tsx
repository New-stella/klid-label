import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { RoleGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';
import { Role } from '@/lib/api/types';

function renderManage(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route
          path="/manage/users"
          element={
            <RoleGuard allow={[Role.REVIEWER]}>
              <div>USER_MANAGE_PAGE</div>
            </RoleGuard>
          }
        />
        <Route
          path="/manage/settings"
          element={
            <RoleGuard allow={[Role.REVIEWER]}>
              <div>SYSTEM_SETTINGS_PAGE</div>
            </RoleGuard>
          }
        />
        <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
        <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('manage 라우트 RoleGuard', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    useAuthStore.getState().clear();
  });

  it('WORKER가_manage_users_접근시_forbidden_redirect', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderManage('/manage/users');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('WORKER가_manage_settings_접근시_forbidden_redirect', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderManage('/manage/settings');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('REVIEWER는_manage_users_settings_접근_허용', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    const { unmount } = renderManage('/manage/users');
    expect(screen.getByText('USER_MANAGE_PAGE')).toBeInTheDocument();
    unmount();

    renderManage('/manage/settings');
    expect(screen.getByText('SYSTEM_SETTINGS_PAGE')).toBeInTheDocument();
  });
});
