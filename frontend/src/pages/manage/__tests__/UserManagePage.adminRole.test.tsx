// 회귀 가드 — 사용자 관리에서 «관리자»를 지정할 수 있고, 마지막 관리자 강등 거부를 안내한다.
// [@design SCREEN-024] [@design API-004] [@design AC-124] [@design ROLE-004]
//
// ★관리자 선택지가 이 화면에만 있다. 빠지면 관리자를 한 명도 늘릴 수 없고, 마지막 관리자 보호와
//   맞물려 **교대 자체가 막힌다**(부트스트랩 창은 관리자가 생긴 뒤 닫혀 있다).
//
// ★거부(409)는 다시 고를 수 있는 종류라 토스트로 흘려보내지 않는다. 모달을 닫으면 고르던 값과
//   사유가 함께 사라져, 사용자는 무엇이 왜 막혔는지 모른 채 처음부터 다시 해야 한다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { useAdminSessionStore } from '@/features/adminSession/store';
import { apiClient } from '@/lib/api/client';
import { UserManagePage } from '@/pages/manage/UserManagePage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';
import { useAuthStore } from '@/stores/useAuthStore';

/** 관리자 1명 + 검수자 1명 — 부정 단언이 빈 목록 때문에 참이 되지 않게 한다. */
const USERS = {
  content: [
    {
      id: 7,
      loginId: 'admin1',
      name: '관리자1',
      email: 'a1@example.invalid',
      role: 'ADMIN',
      active: true,
      createdAt: '2026-01-02T00:00:00Z',
      lastLoginAt: null,
    },
    {
      id: 8,
      loginId: 'reviewer1',
      name: '검수자1',
      email: 'r1@example.invalid',
      role: 'REVIEWER',
      active: true,
      createdAt: '2026-01-03T00:00:00Z',
      lastLoginAt: null,
    },
  ],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 20,
};

function openWindow() {
  useAdminSessionStore.getState().open({
    token: 'dummy-window',
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
  });
}

/** 지정한 이름의 행에서 수정 모달을 연다. */
async function openEditOf(user: ReturnType<typeof userEvent.setup>, name: string) {
  await waitFor(() => expect(screen.getByText(name)).toBeInTheDocument());
  const row = screen.getByText(name).closest('tr') as HTMLElement;
  await user.click(within(row).getByRole('button', { name: '수정' }));
  return screen.findByRole('dialog');
}

describe('사용자 관리 — 관리자 역할 지정', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/users').reply(200, {
      success: true,
      data: USERS,
      message: null,
      errorCode: null,
    });
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: '1001', role: 'ADMIN', channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
    });
    openWindow();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useAdminSessionStore.getState().clear();
  });

  it('역할_선택지에_관리자가_있고_네_역할이_모두_있다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '검수자1');
    await user.click(within(dialog).getByRole('combobox'));

    const options = (await screen.findAllByRole('option')).map((o) => o.textContent);
    expect(options).toEqual(['관리자', '검수자', '작업자', '포털']);
  });

  it('관리자로_바꾸면_ADMIN_이_그대로_전송된다', async () => {
    // 화면 표시명이 아니라 **계약 값**이 나가는지 본다 — 라벨만 맞고 값이 틀리면 서버가 400 이다.
    const patched: unknown[] = [];
    mock.onPatch('/users/8').reply((config) => {
      patched.push(JSON.parse(config.data as string));
      return [200, { success: true, data: {}, message: null, errorCode: null }];
    });

    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '검수자1');
    await selectRadixOption(user, within(dialog).getByRole('combobox'), '관리자');
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(patched).toHaveLength(1));
    expect(patched[0]).toEqual({ role: 'ADMIN' });
  });
});

describe('사용자 관리 — 마지막 관리자 강등 거부 안내', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/users').reply(200, {
      success: true,
      data: USERS,
      message: null,
      errorCode: null,
    });
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: '1001', role: 'ADMIN', channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
    });
    openWindow();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useAdminSessionStore.getState().clear();
  });

  const DENIED = '마지막 관리자는 다른 역할로 변경할 수 없습니다. 다른 사용자를 관리자로 지정한 뒤 다시 시도하세요.';

  async function demoteLastAdmin() {
    mock.onPatch('/users/7').reply(409, {
      success: false,
      data: null,
      message: DENIED,
      errorCode: 'CONFLICT',
    });

    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '관리자1');
    await selectRadixOption(user, within(dialog).getByRole('combobox'), '검수자');
    await user.click(screen.getByRole('button', { name: '저장' }));
    return user;
  }

  it('서버가_준_사유를_그_자리에_보여준다', async () => {
    // 문구를 화면이 다시 쓰지 않는다 — 규칙이 바뀌면 두 문장이 갈린다.
    await demoteLastAdmin();

    const notice = await screen.findByTestId('edit-user-conflict-notice');
    expect(notice).toHaveTextContent(DENIED);
    expect(notice).toHaveAttribute('role', 'alert');
  });

  it('모달을_닫지_않아_다른_역할을_바로_고를_수_있다', async () => {
    // 닫아 버리면 방금 띄운 안내를 아무도 못 보고, 고르던 값도 사라진다.
    await demoteLastAdmin();

    await screen.findByTestId('edit-user-conflict-notice');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '저장' })).toBeInTheDocument();
  });

  it('유효창_만료_안내와_섞이지_않는다', async () => {
    // ★거부 사유가 둘 다 있는 화면이라 축을 갈라 둔다 — 409 를 403 경로로 흘리면 「관리자 확인이
    //   만료됐다」는 **거짓 안내**가 뜨고, 사용자가 재확인을 반복해도 영원히 저장되지 않는다.
    await demoteLastAdmin();

    await screen.findByTestId('edit-user-conflict-notice');
    expect(screen.queryByTestId('edit-user-session-expired')).toBeNull();
  });

  it('다른_사용자를_다시_열면_이전_거부_사유가_남지_않는다', async () => {
    const user = await demoteLastAdmin();
    await screen.findByTestId('edit-user-conflict-notice');

    await user.click(screen.getByRole('button', { name: '취소' }));
    await openEditOf(user, '검수자1');

    expect(screen.queryByTestId('edit-user-conflict-notice')).toBeNull();
  });
});
