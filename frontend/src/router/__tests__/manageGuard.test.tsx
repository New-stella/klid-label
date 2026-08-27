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

/**
 * ⚠ 이 파일은 라우트 트리를 **테스트 안에서 새로 조립**하므로 프로덕션 배선을 지키지 않는다 —
 * `RoleGuard` 자체의 동작 예시일 뿐이다. 실제 배선(어느 경로에 어떤 가드가 걸려 있는가)의 회귀
 * 가드는 프로덕션 라우터를 읽는 `videoReviewerOnlyGuard`·`adminRouteGuard` 가 갖는다.
 *
 * ⚠ 「사용자 관리」는 관리자 페이지(`/admin/users`)로 옮겨가 이 파일의 대상이 아니다 — 그 화면은
 * 역할 가드 위에 관리자 유효창이 가산되므로 축이 다르다(`adminRouteGuard`).
 */
describe('manage 라우트 RoleGuard', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    useAuthStore.getState().clear();
  });

  it('WORKER가_manage_settings_접근시_forbidden_redirect', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderManage('/manage/settings');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('REVIEWER는_manage_settings_접근_허용', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderManage('/manage/settings');
    expect(screen.getByText('SYSTEM_SETTINGS_PAGE')).toBeInTheDocument();
  });
});
