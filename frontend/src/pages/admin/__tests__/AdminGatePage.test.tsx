// 회귀 가드 — 관리자 페이지 진입 화면. [@design SCREEN-040] [@design API-194]
//
// ★핵심은 «되돌려 보내기»다. 유효창이 없어 이 화면이 대신 열렸다면, 확인을 통과한 뒤 **원래
//   가려던 화면**으로 돌아가야 한다. 돌아가지 않고 늘 기본 화면으로 보내면 사용자가 누른 메뉴와
//   도착지가 달라져, 눌러도 다른 데로 간다는 인상을 준다.
//
// ★실패 사유를 가르지 않는 것도 사양이다 — 패스워드 불일치·권한 없음·시도 초과를 구분해 알리면
//   그 응답이 공격자에게 상태를 알려주는 신호가 된다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { MemoryRouter, Navigate, Route, Routes } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';

import { useAdminSessionStore } from '@/features/adminSession/store';
import { apiClient } from '@/lib/api/client';
import { AdminGatePage } from '@/pages/admin/AdminGatePage';
import { AdminSessionGuard } from '@/router/adminSessionGuard';
import { createTestQueryClient } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const ISSUED = {
  token: 'dummy-window',
  expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
};

/**
 * 진입 게이트 + 관리 화면을 한 라우터에 올린다.
 *
 * 게이트가 실제로 «되돌려 보내는지» 는 두 화면이 함께 있어야만 관측된다 — 게이트만 단독으로
 * 렌더하면 이동이 일어나도 도착지가 없어 아무 일도 없는 것처럼 보인다.
 */
function renderGateWith(entry: string) {
  const client = createTestQueryClient();
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/admin" element={<AdminGatePage />} />
          <Route
            path="/admin/users"
            element={
              <AdminSessionGuard>
                <div>USERS_PAGE</div>
              </AdminSessionGuard>
            }
          />
          <Route
            path="/admin/endpoints"
            element={
              <AdminSessionGuard>
                <div>ENDPOINTS_PAGE</div>
              </AdminSessionGuard>
            }
          />
          <Route path="/dashboard" element={<div>DASHBOARD</div>} />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminGatePage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: '1001', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
    });
    useAdminSessionStore.getState().clear();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useAdminSessionStore.getState().clear();
  });

  it('★확인을_통과하면_원래_가려던_화면으로_되돌려_보낸다', async () => {
    mock.onPost('/manage/admin-session').reply(200, {
      success: true,
      data: ISSUED,
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    // 유효창 없이 연동 주소 화면을 열면 게이트가 대신 뜬다.
    renderGateWith('/admin/endpoints');
    expect(await screen.findByRole('heading', { name: '관리자 확인' })).toBeInTheDocument();
    expect(screen.queryByText('ENDPOINTS_PAGE')).toBeNull();

    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin-value');
    await user.click(screen.getByRole('button', { name: '확인' }));

    // ★기본 화면(`/admin/users`)이 아니라 **원래 가려던** 화면으로 돌아간다.
    await waitFor(() => expect(screen.getByText('ENDPOINTS_PAGE')).toBeInTheDocument());
    expect(screen.queryByText('USERS_PAGE')).toBeNull();
  });

  it('되돌아갈_곳을_모르면_관리자_기본_화면으로_보낸다', async () => {
    mock.onPost('/manage/admin-session').reply(200, {
      success: true,
      data: ISSUED,
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    // 주소를 직접 입력해 게이트로 바로 들어온 경우 — 이동 상태에 출발지가 없다.
    renderGateWith('/admin');
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin-value');
    await user.click(screen.getByRole('button', { name: '확인' }));

    await waitFor(() => expect(screen.getByText('USERS_PAGE')).toBeInTheDocument());
  });

  it('확인에_성공하면_유효창이_열려_다른_관리_화면도_통과한다', async () => {
    mock.onPost('/manage/admin-session').reply(200, {
      success: true,
      data: ISSUED,
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderGateWith('/admin');
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin-value');
    await user.click(screen.getByRole('button', { name: '확인' }));

    await waitFor(() => expect(screen.getByText('USERS_PAGE')).toBeInTheDocument());
    // 진입 화면이 연 창을 **다른 화면이 함께 쓴다** — 화면마다 다시 물으면 이 분리의 의미가 없다.
    expect(useAdminSessionStore.getState().token).toBe(ISSUED.token);
  });

  it('★실패하면_사유를_가르지_않고_같은_문구로_알린다', async () => {
    mock.onPost('/manage/admin-session').reply(401, {
      success: false,
      data: null,
      message: '관리자 패스워드가 일치하지 않습니다.',
      errorCode: 'UNAUTHORIZED',
    });

    const user = userEvent.setup();
    renderGateWith('/admin');
    await user.type(screen.getByLabelText('관리자 패스워드'), 'wrong-value');
    await user.click(screen.getByRole('button', { name: '확인' }));

    const alert = await screen.findByText(/패스워드를 다시 확인해 주세요/);
    expect(alert).toBeInTheDocument();
    // 남은 시도 횟수·제한 기준·해제 시각을 알리지 않는다.
    expect(document.body.textContent).not.toMatch(/남은 시도|회 남|제한 해제/);
    // 입력한 값을 화면에 되돌려 주지 않는다.
    expect(document.body.textContent).not.toContain('wrong-value');
    // 유효창은 열리지 않았다.
    expect(useAdminSessionStore.getState().token).toBeNull();
  });

  it('입력칸은_가려지고_자동완성_저장을_유도하지_않는다', () => {
    renderGateWith('/admin');
    const input = screen.getByLabelText('관리자 패스워드');
    expect(input).toHaveAttribute('type', 'password');
    // ⚠ 게시 렌더에서는 서버가 이 속성을 떼어 낸다 — 렌더만 보고 옮기면 빠지므로 코드로 지킨다.
    expect(input).toHaveAttribute('autocomplete', 'off');
  });

  it('이미_열려_있으면_묻지_않고_바로_보낸다', async () => {
    // 열려 있는데 또 물어보면 사용자는 앞선 확인이 실패한 줄 안다.
    useAdminSessionStore.getState().open(ISSUED);
    renderGateWith('/admin');
    await waitFor(() => expect(screen.getByText('USERS_PAGE')).toBeInTheDocument());
  });
});
