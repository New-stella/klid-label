// 회귀 가드 — 관리자 패스워드 교체 화면. [@design SCREEN-041] [@design API-223]
//
// ★가장 중요한 것: **교체가 성공하면 그 즉시 유효창을 버려야 한다.**
//   서명키가 현재 패스워드 해시에서 유도되므로, 교체 순간부터 **방금 이 요청에 쓴 토큰까지
//   전부 403** 이다. 버리지 않으면 사용자가 이어지는 관리 조작마다 영문 모를 거부를 반복해 받고,
//   화면은 「아직 열려 있다」고 표시한다 — 화면과 서버 판정이 정반대로 갈린다.
//
// ★그리고 그 사실을 **오류처럼 보이게 하지 않는다.** 의도한 결과라고 알리지 않으면 사람이 방금
//   바꾼 값을 의심해 되돌리려 든다.
//
// ⚠ 여기서 화면 밖으로 쫓아내지 않는 것도 사양이다 — 진입 게이트는 첫 렌더 한 번만 판정하므로
//   유효창을 버려도 이 화면이 유지되어 성공 안내를 볼 수 있다(`AdminSessionGuard`).

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { ADMIN_SESSION_HEADER } from '@/features/adminSession/api';
import { useAdminSessionStore } from '@/features/adminSession/store';
import { apiClient } from '@/lib/api/client';
import { AdminPasswordPage } from '@/pages/admin/AdminPasswordPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const WINDOW_TOKEN = 'issued-window-value';

function openWindow(minutes = 10) {
  useAdminSessionStore.getState().open({
    token: WINDOW_TOKEN,
    expiresAt: new Date(Date.now() + minutes * 60 * 1000).toISOString(),
  });
}

async function fillChangeForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('현재 패스워드'), 'old-value-1');
  await user.type(screen.getByLabelText('새 패스워드'), 'new-value-12');
  await user.type(screen.getByLabelText('새 패스워드 확인'), 'new-value-12');
}

describe('AdminPasswordPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: '1001', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useAdminSessionStore.getState().clear();
  });

  it('★교체에_성공하면_보유한_유효창을_즉시_버린다', async () => {
    openWindow();
    mock.onPut('/manage/admin-password').reply(200, {
      success: true,
      data: null,
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<AdminPasswordPage />, { initialEntries: ['/admin/password'] });
    await fillChangeForm(user);
    await user.click(screen.getByRole('button', { name: '패스워드 바꾸기' }));

    await waitFor(() => {
      expect(screen.getByTestId('admin-password-changed')).toBeInTheDocument();
    });

    // ★스토어를 **직접** 본다 — 화면 문구만 보면 「안내는 떴는데 토큰은 살아 있다」를 못 잡는다.
    expect(useAdminSessionStore.getState().token).toBeNull();
    expect(useAdminSessionStore.getState().expiresAtMs).toBeNull();
  });

  it('교체_성공은_오류가_아니라_의도된_결과로_안내한다', async () => {
    openWindow();
    mock.onPut('/manage/admin-password').reply(200, {
      success: true,
      data: null,
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<AdminPasswordPage />, { initialEntries: ['/admin/password'] });
    await fillChangeForm(user);
    await user.click(screen.getByRole('button', { name: '패스워드 바꾸기' }));

    const panel = await screen.findByTestId('admin-password-changed');
    expect(panel).toHaveTextContent('관리자 패스워드를 바꿨습니다');
    expect(panel).toHaveTextContent('의도한 결과');
    // 조회는 계속 되므로 화면이 통째로 잠긴 것처럼 안내하지 않는다.
    expect(panel).toHaveTextContent('조회는 검수자 권한만으로 계속 됩니다');
  });

  it('교체_요청에_유효창_헤더와_두_값이_실린다', async () => {
    openWindow();
    let sent: { headers?: Record<string, unknown>; body?: string } = {};
    mock.onPut('/manage/admin-password').reply((config) => {
      sent = {
        headers: config.headers as Record<string, unknown>,
        body: config.data as string,
      };
      return [200, { success: true, data: null, message: null, errorCode: null }];
    });

    const user = userEvent.setup();
    renderWithProviders(<AdminPasswordPage />, { initialEntries: ['/admin/password'] });
    await fillChangeForm(user);
    await user.click(screen.getByRole('button', { name: '패스워드 바꾸기' }));

    await waitFor(() => expect(sent.body).toBeTruthy());
    expect(sent.headers?.[ADMIN_SESSION_HEADER]).toBe(WINDOW_TOKEN);
    const body = JSON.parse(sent.body as string) as Record<string, string>;
    // 현재 패스워드를 다시 받는다 — 유효창 탈취가 곧 자격 완전 탈취가 되지 않게 하는 자리다.
    expect(Object.keys(body).sort()).toEqual(['currentPassword', 'newPassword']);
  });

  it('유효창이_없으면_요청을_보내지_않고_확인_창을_먼저_연다', async () => {
    // 보내 봐야 403 이고, 그 거부는 화면에서 「이유를 알 수 없는 실패」로 보인다.
    useAdminSessionStore.getState().clear();
    let called = 0;
    mock.onPut('/manage/admin-password').reply(() => {
      called += 1;
      return [200, { success: true, data: null, message: null, errorCode: null }];
    });

    const user = userEvent.setup();
    renderWithProviders(<AdminPasswordPage />, { initialEntries: ['/admin/password'] });
    await fillChangeForm(user);
    await user.click(screen.getByRole('button', { name: '패스워드 바꾸기' }));

    // 확인 창의 입력칸으로 열렸음을 확인한다.
    // ⚠ `getByRole('dialog')` 로 기다리지 않는다 — 같은 role 이 여럿일 때 엉뚱한 것을 집어 온다.
    expect(await screen.findByLabelText('관리자 패스워드')).toBeInTheDocument();
    expect(called).toBe(0);
  });

  it('새_값_두_칸이_어긋나면_제출되지_않는다', async () => {
    openWindow();
    let called = 0;
    mock.onPut('/manage/admin-password').reply(() => {
      called += 1;
      return [200, { success: true, data: null, message: null, errorCode: null }];
    });

    const user = userEvent.setup();
    renderWithProviders(<AdminPasswordPage />, { initialEntries: ['/admin/password'] });
    await user.type(screen.getByLabelText('현재 패스워드'), 'old-value-1');
    await user.type(screen.getByLabelText('새 패스워드'), 'new-value-12');
    await user.type(screen.getByLabelText('새 패스워드 확인'), 'new-value-99');

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '패스워드 바꾸기' })).toBeDisabled();
    });
    expect(called).toBe(0);
  });

  it('현재_값과_같은_새_값은_화면에서_먼저_막는다', async () => {
    // 바뀌지도 않았는데 열려 있던 유효창만 전부 끊기는 일을 막는다.
    openWindow();
    const user = userEvent.setup();
    renderWithProviders(<AdminPasswordPage />, { initialEntries: ['/admin/password'] });
    await user.type(screen.getByLabelText('현재 패스워드'), 'same-value-1');
    await user.type(screen.getByLabelText('새 패스워드'), 'same-value-1');
    await user.type(screen.getByLabelText('새 패스워드 확인'), 'same-value-1');

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '패스워드 바꾸기' })).toBeDisabled();
    });
  });

  it('세_입력칸이_모두_가려지고_자동완성_저장을_유도하지_않는다', () => {
    openWindow();
    renderWithProviders(<AdminPasswordPage />, { initialEntries: ['/admin/password'] });

    for (const label of ['현재 패스워드', '새 패스워드', '새 패스워드 확인']) {
      const input = screen.getByLabelText(label);
      expect(input).toHaveAttribute('type', 'password');
      // ⚠ 게시 렌더에서는 서버가 이 속성을 떼어 낸다 — 렌더만 보고 옮기면 빠지므로 코드로 지킨다.
      expect(input).toHaveAttribute('autocomplete', 'off');
    }
  });

  it('실패_안내에_입력한_값을_싣지_않고_권한과_만료를_구분해_알리지_않는다', async () => {
    openWindow();
    mock.onPut('/manage/admin-password').reply(403, {
      success: false,
      data: null,
      message: '권한이 없습니다.',
      errorCode: 'FORBIDDEN',
    });

    const user = userEvent.setup();
    renderWithProviders(<AdminPasswordPage />, { initialEntries: ['/admin/password'] });
    await fillChangeForm(user);
    await user.click(screen.getByRole('button', { name: '패스워드 바꾸기' }));

    // ⚠ 같은 문구가 화면 여러 곳(상태 줄·거부 안내)에 나올 수 있으므로 **거부 안내 상자**를
    //   직접 집는다 — 텍스트만으로 집으면 「Found multiple elements」로 엉뚱한 자리를 본다.
    const alert = await screen.findByTestId('admin-password-error');
    expect(alert).toHaveTextContent('관리자 확인이 필요합니다');
    // 입력한 값이 어디에도 나타나지 않는다.
    expect(document.body.textContent).not.toContain('old-value-1');
    expect(document.body.textContent).not.toContain('new-value-12');
    // 성공 안내가 뜨지 않는다(실패인데 성공처럼 보이면 안 된다).
    expect(screen.queryByTestId('admin-password-changed')).toBeNull();
  });
});
