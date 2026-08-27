// 회귀 가드 — 사용자 관리의 «조회 / 쓰기» 요건 분리. [@design SCREEN-024] [@design API-004]
//
// ★두 축을 **짝으로** 단언한다.
//   ① 쓰기(역할 변경)에는 관리자 유효창이 실린다.
//   ② 조회(목록)에는 실리지 않는다 — 「일관성」을 이유로 조회에까지 얹으면 이 화면뿐 아니라
//      작업 배정 흐름이 함께 끊긴다.
//   ①만 보면 조회 축이 조용히 바뀌어도 통과하고, ②만 보면 쓰기 축이 빠져도 통과한다.
//
// ★만료를 거부 코드로만 알리고 끝내지 않는다 — 만료 사실을 안내하고 재확인을 받은 뒤
//   **저장을 이어서 시도**한다. 그때 모달을 닫아 버리면 방금 띄운 안내를 아무도 못 본다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { ADMIN_SESSION_HEADER } from '@/features/adminSession/api';
import { useAdminSessionStore } from '@/features/adminSession/store';
import { apiClient } from '@/lib/api/client';
import { UserManagePage } from '@/pages/manage/UserManagePage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';
import { useAuthStore } from '@/stores/useAuthStore';

const WINDOW_TOKEN = 'dummy-window';
const NEXT_TOKEN = 'dummy-window-2';

const USERS = {
  content: [
    {
      id: 7,
      loginId: 'worker1',
      name: '작업자1',
      email: 'w1@example.invalid',
      role: 'WORKER',
      active: true,
      createdAt: '2026-01-02T00:00:00Z',
    },
  ],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
};

function openWindow(token = WINDOW_TOKEN) {
  useAdminSessionStore.getState().open({
    token,
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
  });
}

/**
 * 역할 수정 모달을 열고 다른 역할을 고른다.
 *
 * ⚠ 화면에는 필터바의 역할 select 도 있어 `getByRole('combobox')` 가 둘을 집는다 — 모달 안으로
 *   범위를 좁혀 고른다. (이 시점에는 관리자 확인 창이 아직 없어 dialog 는 하나뿐이다.)
 */
async function openEditAndPickReviewer(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getByText('작업자1')).toBeInTheDocument());
  await user.click(screen.getByRole('button', { name: '수정' }));
  const dialog = await screen.findByRole('dialog');
  await selectRadixOption(user, within(dialog).getByRole('combobox'), '검수자');
}

describe('UserManagePage — 관리자 유효창', () => {
  let mock: MockAdapter;
  let patchHeaders: Array<Record<string, unknown>>;
  let getHeaders: Array<Record<string, unknown>>;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    patchHeaders = [];
    getHeaders = [];
    mock.onGet('/users').reply((config) => {
      getHeaders.push(config.headers as Record<string, unknown>);
      return [200, { success: true, data: USERS, message: null, errorCode: null }];
    });
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

  it('★역할_변경에는_유효창이_실리고_목록_조회에는_실리지_않는다', async () => {
    openWindow();
    mock.onPatch('/users/7').reply((config) => {
      patchHeaders.push(config.headers as Record<string, unknown>);
      return [200, { success: true, data: {}, message: null, errorCode: null }];
    });

    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });
    await openEditAndPickReviewer(user);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(patchHeaders).toHaveLength(1));
    expect(patchHeaders[0]![ADMIN_SESSION_HEADER]).toBe(WINDOW_TOKEN);

    // 조회 축 — 한 번이라도 헤더가 실렸으면 실패다.
    expect(getHeaders.length).toBeGreaterThan(0);
    for (const headers of getHeaders) {
      expect(headers[ADMIN_SESSION_HEADER]).toBeUndefined();
    }
  });

  it('유효창이_없으면_요청을_보내지_않고_확인_창을_먼저_연다', async () => {
    useAdminSessionStore.getState().clear();
    mock.onPatch('/users/7').reply((config) => {
      patchHeaders.push(config.headers as Record<string, unknown>);
      return [200, { success: true, data: {}, message: null, errorCode: null }];
    });

    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });
    await openEditAndPickReviewer(user);
    await user.click(screen.getByRole('button', { name: '저장' }));

    // ⚠ `findByRole('dialog')` 로 기다리지 않는다 — 수정 모달이 이미 열려 있어 그것을 집어 온다.
    expect(await screen.findByLabelText('관리자 패스워드')).toBeInTheDocument();
    expect(patchHeaders).toHaveLength(0);
  });

  it('★서버가_만료로_거부하면_안내하고_재확인_뒤_저장을_이어서_시도한다', async () => {
    openWindow();
    let attempt = 0;
    mock.onPatch('/users/7').reply((config) => {
      attempt += 1;
      patchHeaders.push(config.headers as Record<string, unknown>);
      if (attempt === 1) {
        return [403, { success: false, data: null, message: '권한이 없습니다.', errorCode: 'FORBIDDEN' }];
      }
      return [200, { success: true, data: {}, message: null, errorCode: null }];
    });
    mock.onPost('/manage/admin-session').reply(200, {
      success: true,
      data: {
        token: NEXT_TOKEN,
        expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });
    await openEditAndPickReviewer(user);
    await user.click(screen.getByRole('button', { name: '저장' }));

    // ① 거부를 조용히 삼키지 않는다 — 만료 사실을 알린다.
    const notice = await screen.findByTestId('edit-user-session-expired');
    expect(notice).toHaveTextContent('관리자 확인이 만료되어 저장하지 못했습니다');
    // ② 수정 모달을 닫지 않는다 — 닫으면 방금 띄운 안내를 사용자가 보지 못한다.
    expect(screen.getByTestId('edit-user-admin-session')).toBeInTheDocument();

    // ③ 재확인하면 **저장을 이어서 시도**한다.
    await user.type(await screen.findByLabelText('관리자 패스워드'), 'admin-value');
    await user.click(screen.getByRole('button', { name: '인증' }));

    await waitFor(() => expect(patchHeaders).toHaveLength(2));
    // ★새로 발급된 토큰이 실려야 한다 — 훅의 반환값은 다음 렌더에야 갱신되므로, 그 값을 그대로
    //   쓰면 방금 연 창을 두고도 옛 토큰(또는 빈 값)으로 요청이 나간다.
    expect(patchHeaders[1]![ADMIN_SESSION_HEADER]).toBe(NEXT_TOKEN);
  });

  it('모달에_남은_시간이_표시된다', async () => {
    openWindow();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });
    await waitFor(() => expect(screen.getByText('작업자1')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: '수정' }));

    // 긴 입력을 마치고 저장을 눌렀을 때 비로소 만료를 알게 되는 일을 없앤다.
    const status = await screen.findByTestId('edit-user-admin-session');
    expect(within(status).getByText(/관리자 확인됨 — \d+:\d{2} 남음/)).toBeInTheDocument();
  });

  it('유효창이_없어도_목록은_보인다', async () => {
    // 조회는 검수자 권한만으로 된다 — 잠긴 것은 저장뿐이다.
    useAdminSessionStore.getState().clear();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });
    await waitFor(() => expect(screen.getByText('작업자1')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '수정' })).toBeInTheDocument();
  });
});
