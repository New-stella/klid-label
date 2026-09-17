// 회귀 가드 — 사용자 관리 수정 모달의 «표시 이름» 편집.
// [@design SCREEN-024] [@design API-004] [@design UC-030] [@design AC-1018] [@design AC-1019]
//
// 고정하는 계약:
//  ① 바꾼 축만 보낸다 — 이름만 / 역할과 함께 / 같은 값이면 보내지 않음.
//  ② 빈 값·공백만·폭 초과를 <b>저장을 누르기 전에</b> 그 자리에서 막는다.
//  ③ 서버가 준 400 문구를 그대로 보여주지 않는다 — <b>두 모양</b>(필드 경로가 있는 진단 문자열 ·
//     사람이 읽는 단일 문장) 모두.
//  ④ 미배정 사용자도 이름만 고쳐 저장할 수 있다(2026-09-16 완화 — 구 규칙은 이것까지 막았다).
//  ⑤ 유효창은 창구 전체에 걸린다 — 이름만 고치는 저장에도 실린다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';

import { ADMIN_SESSION_HEADER } from '@/features/adminSession/api';
import { useAdminSessionStore } from '@/features/adminSession/store';
import { USER_NM_MAX_LENGTH } from '@/features/user/displayNameRules';
import { apiClient } from '@/lib/api/client';
import { UserManagePage } from '@/pages/manage/UserManagePage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';
import { useAuthStore } from '@/stores/useAuthStore';

const WINDOW_TOKEN = 'dummy-window';

/** 역할이 배정된 사용자 1건 + 미배정 사용자 1건 — 두 축을 한 화면에서 본다. */
const USERS = {
  content: [
    {
      id: 7,
      loginId: 'worker1',
      name: '김작업',
      role: 'WORKER',
      active: true,
      createdAt: '2026-01-02T00:00:00Z',
      lastLoginAt: null,
    },
    {
      id: 9,
      loginId: 'newbie',
      name: '신규사용자',
      role: null,
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

describe('사용자 관리 — 표시 이름 편집', () => {
  let mock: MockAdapter;
  /** PATCH 요청 본문. 어느 축이 실렸는지를 여기서 판정한다. */
  let patched: Array<Record<string, unknown>>;
  let patchHeaders: Array<Record<string, unknown>>;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    patched = [];
    patchHeaders = [];
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
    useAdminSessionStore.getState().open({
      token: WINDOW_TOKEN,
      expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useAdminSessionStore.getState().clear();
  });

  function acceptPatch(userNo: number) {
    mock.onPatch(`/users/${userNo}`).reply((config) => {
      patched.push(JSON.parse(config.data as string) as Record<string, unknown>);
      patchHeaders.push(config.headers as Record<string, unknown>);
      return [200, { success: true, data: {}, message: null, errorCode: null }];
    });
  }

  /** 지정한 이름의 행에서 수정 모달을 연다. */
  async function openEditOf(user: UserEvent, name: string): Promise<HTMLElement> {
    await waitFor(() => expect(screen.getByText(name)).toBeInTheDocument());
    const row = screen.getByText(name).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: '수정' }));
    return screen.findByRole('dialog');
  }

  /** 표시 이름 입력칸을 새 값으로 갈아 넣는다. */
  async function retypeName(user: UserEvent, dialog: HTMLElement, value: string) {
    const input = within(dialog).getByLabelText('표시 이름');
    await user.clear(input);
    if (value !== '') await user.type(input, value);
    return input;
  }

  // ── ① 바꾼 축만 보낸다 ────────────────────────────────────────────────

  it('★이름만_고치면_이름만_보낸다_역할_칸은_실리지_않는다', async () => {
    // 안 바꾼 역할을 현재 값으로 채워 보내면 서버가 그것을 「역할 변경 요청」으로 받아,
    // 대상이 마지막 관리자일 때 이름만 고치려던 저장이 409 로 막힌다([@design AC-1019]).
    acceptPatch(7);
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '김작업');
    await retypeName(user, dialog, '김작업새이름');
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(patched).toHaveLength(1));
    expect(patched[0]).toEqual({ userNm: '김작업새이름' });
    expect(patched[0]).not.toHaveProperty('role');
  });

  it('역할과_이름을_함께_고치면_두_축이_같은_요청에_실린다', async () => {
    acceptPatch(7);
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '김작업');
    await retypeName(user, dialog, '김검수');
    await selectRadixOption(user, within(dialog).getByRole('combobox'), '검수자');
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(patched).toHaveLength(1));
    expect(patched[0]).toEqual({ role: 'REVIEWER', userNm: '김검수' });
  });

  it('★같은_이름이면_보내지_않는다_공백만_덧붙인_경우도_같다', async () => {
    // 창구: "같은 이름을 다시 보내면 아무것도 바꾸지 않는다 — 수정일시도 밀지 않는다".
    // 화면이 정규화 전 원문으로 비교하면 공백 하나 때문에 헛된 왕복과 수정일시 갱신이 생긴다.
    acceptPatch(7);
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '김작업');
    await retypeName(user, dialog, '  김작업  ');

    // 이름은 바뀌지 않았으므로 저장이 잠긴다 — 역할도 그대로다.
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(screen.getByTestId('edit-user-unchanged-notice')).toBeInTheDocument();

    // 역할만 바꿔 저장하면 이름 칸은 실리지 않는다.
    await selectRadixOption(user, within(dialog).getByRole('combobox'), '검수자');
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(patched).toHaveLength(1));
    expect(patched[0]).toEqual({ role: 'REVIEWER' });
    expect(patched[0]).not.toHaveProperty('userNm');
  });

  // ── ② 저장 전에 막는다 ───────────────────────────────────────────────

  it('★빈_이름은_저장_전에_막히고_요청이_나가지_않는다', async () => {
    acceptPatch(7);
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '김작업');
    await retypeName(user, dialog, '');

    expect(within(dialog).getByText('표시 이름을 입력하세요.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    // 저장 단추가 잠긴 것과 별개로, 요청이 실제로 나가지 않는지 센다.
    expect(patched).toHaveLength(0);
  });

  it('공백만_있는_이름도_저장_전에_막힌다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '김작업');
    await retypeName(user, dialog, '   ');

    expect(within(dialog).getByText('표시 이름을 입력하세요.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  it('★폭을_넘는_이름은_잘리지_않고_막힌다', async () => {
    // 입력칸에 `maxLength` 를 걸면 브라우저가 말없이 잘라 **다른 이름이 저장된다** — 창구가
    // "잘라 담지 않고 거절한다"로 막으려는 바로 그 실패다. 잘리지 않았음을 값으로 확인한다.
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '김작업');
    const tooLong = '가'.repeat(USER_NM_MAX_LENGTH + 1);
    const input = (await retypeName(user, dialog, tooLong)) as HTMLInputElement;

    expect(input.value).toHaveLength(USER_NM_MAX_LENGTH + 1); // 브라우저가 자르지 않았다
    // ⚠ `/자 이하여야 합니다/` 처럼 느슨하게 찾지 않는다 — 같은 낱말이 입력칸 도움말
    //   ("비워 둘 수 없으며 N자 이하여야 합니다")에도 있어 두 요소가 함께 잡힌다.
    //   문구 전체를 상수로 조립해 **몇 자인지까지** 못박는다.
    expect(
      within(dialog).getByText(
        `표시 이름은 ${USER_NM_MAX_LENGTH}자 이하여야 합니다. 지금 ${USER_NM_MAX_LENGTH + 1}자입니다.`,
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  it('★오류_문구가_그_입력칸에_붙어_있다_화면_아무_데나_있는_것이_아니다', async () => {
    // 「안내가 화면 어딘가 있다」는 단언은 **문구를 엉뚱한 자리로 옮겨도 통과한다**. 입력칸이
    // 자기 오류로 그 문구를 가리키는지(aria-describedby)를 봐야 자리가 고정된다 —
    // 보조기술 사용자에게는 이 연결이 곧 「어느 칸이 틀렸는가」다.
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '김작업');
    const input = await retypeName(user, dialog, '');

    const error = within(dialog).getByText('표시 이름을 입력하세요.');
    expect(error.id).not.toBe('');
    expect((input.getAttribute('aria-describedby') ?? '').split(/\s+/)).toContain(error.id);
    expect(input).toHaveAttribute('aria-invalid', 'true');
  });

  it('고치면_오류가_사라지고_저장이_풀린다', async () => {
    // 0 을 기대하는 단언만 두면 「처음부터 아무것도 안 그린다」와 구분되지 않는다 — 정상값에서
    // 실제로 열리는 짝을 함께 둔다.
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '김작업');
    await retypeName(user, dialog, '');
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();

    await retypeName(user, dialog, '고친이름');

    await waitFor(() => expect(screen.getByRole('button', { name: '저장' })).toBeEnabled());
    expect(within(dialog).queryByText('표시 이름을 입력하세요.')).toBeNull();
  });

  // ── ③ 서버 400 문구를 그대로 보여주지 않는다 ──────────────────────────

  /** 서버가 400 을 내는 **두 모양** — 둘 다 사용자에게 그대로 보이면 안 된다. */
  const 진단문자열 = 'userNm: 크기가 1에서 100 사이여야 합니다';
  const 단일문장 = '입력값이 유효하지 않습니다.';

  async function saveAndGetRejected(user: UserEvent, message: string) {
    mock.onPatch('/users/7').reply(400, {
      success: false,
      data: null,
      message,
      errorCode: 'INVALID_INPUT',
    });
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });
    const dialog = await openEditOf(user, '김작업');
    await retypeName(user, dialog, '새이름');
    await user.click(screen.getByRole('button', { name: '저장' }));
    return dialog;
  }

  it('★서버_400의_필드_경로가_화면에_나타나지_않는다', async () => {
    const user = userEvent.setup();
    const dialog = await saveAndGetRejected(user, 진단문자열);

    // 양성 짝 — 화면이 자기 문구로 실제로 안내한다(부재 단언만 두면 아무것도 안 그려도 통과한다).
    const notice = await screen.findByTestId('edit-user-invalid-notice');
    expect(notice).toHaveAttribute('role', 'alert');
    expect(within(dialog).getByTestId('edit-user-invalid-notice')).toBeInTheDocument();

    // 토스트까지 포함해 화면 어디에도 서버 문장·필드 이름이 없어야 한다.
    expect(document.body.textContent).not.toContain('userNm');
    expect(document.body.textContent).not.toContain(진단문자열);
  });

  it('★사람이_읽는_단일_문장_모양의_400도_그대로_보여주지_않는다', async () => {
    // 같은 창구가 같은 400 을 두 모양으로 낸다 — 한 모양만 결박하면 나머지가 조용히 샌다.
    const user = userEvent.setup();
    await saveAndGetRejected(user, 단일문장);

    await screen.findByTestId('edit-user-invalid-notice');
    expect(document.body.textContent).not.toContain(단일문장);
  });

  it('★400_안내는_이름을_고치면_사라진다_이미_고친_값_위에_남지_않는다', async () => {
    // 해제 지점이 저장·모달 개폐뿐이면, 사용자가 이름을 고쳐도 「저장할 수 없습니다」가 그대로
    // 떠 있어 **방금 고친 값까지 거부된 것처럼** 읽힌다. 거부는 보낸 값에 대한 것이지 지금 칸에
    // 든 값에 대한 것이 아니다.
    //
    // ⚠ 「사라진다」는 부재 단언이라 혼자 두면 **애초에 뜨지 않아도 통과한다** — 고치기 전에
    //   실제로 떠 있었음을 먼저 단언해 양성 짝을 만든다.
    const user = userEvent.setup();
    const dialog = await saveAndGetRejected(user, 진단문자열);

    // 양성 짝 — 고치기 전에는 안내가 떠 있다.
    expect(await screen.findByTestId('edit-user-invalid-notice')).toBeInTheDocument();

    await retypeName(user, dialog, '다시고친이름');

    await waitFor(() => expect(screen.queryByTestId('edit-user-invalid-notice')).toBeNull());
    // 안내만 사라지고 모달과 고친 값은 그대로다 — 안내를 지우려고 모달을 닫으면 고치던 값을 잃는다.
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('표시 이름')).toHaveValue('다시고친이름');
  });

  it('400_이어도_모달을_닫지_않아_바로_고쳐_쓸_수_있다', async () => {
    const user = userEvent.setup();
    await saveAndGetRejected(user, 진단문자열);

    await screen.findByTestId('edit-user-invalid-notice');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByLabelText('표시 이름')).toHaveValue('새이름');
    // 유효창 만료 안내와 섞이지 않는다 — 재확인을 반복해도 해결되지 않는 거짓 안내가 된다.
    expect(screen.queryByTestId('edit-user-session-expired')).toBeNull();
  });

  // ── ④ 미배정 사용자 완화 ─────────────────────────────────────────────

  it('★미배정_사용자도_이름만_고쳐_저장할_수_있다', async () => {
    // 구 규칙은 "역할을 고르기 전까지 **저장**이 잠긴다"라, 역할을 아직 정하지 못한 사용자의
    // 이름을 고칠 방법이 아예 없었다. 사양이 "역할을 고르기 전까지 **역할이** 저장되지 않는다"로
    // 좁혀졌다(@design SCREEN-024 v37).
    acceptPatch(9);
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '신규사용자');
    // 미배정 안내는 그대로 뜬다 — 상태를 알리는 자리이지 저장을 막는 표시가 아니다.
    expect(within(dialog).getByTestId('edit-user-unassigned-notice')).toBeInTheDocument();

    await retypeName(user, dialog, '이름정정');
    await waitFor(() => expect(screen.getByRole('button', { name: '저장' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(patched).toHaveLength(1));
    // 고르지 않은 역할이 딸려 나가지 않는다 — 사용자가 고른 적 없는 역할이 저장되면 안 된다.
    expect(patched[0]).toEqual({ userNm: '이름정정' });
  });

  it('미배정_사용자가_아무것도_바꾸지_않으면_여전히_저장이_잠긴다', async () => {
    // 완화가 「아무 때나 저장된다」로 넘어가지 않는지 반대편을 함께 고정한다.
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    await openEditOf(user, '신규사용자');
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(patched).toHaveLength(0);
  });

  // ── ⑤ 유효창은 창구 전체에 걸린다 ────────────────────────────────────

  it('★이름만_고치는_저장에도_관리자_유효창이_실린다', async () => {
    // 유효창을 「역할 변경」 축에만 배선하면 이름만 고치는 저장이 헤더 없이 나가 403 을 받는다.
    acceptPatch(7);
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });

    const dialog = await openEditOf(user, '김작업');
    await retypeName(user, dialog, '이름만고침');
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(patchHeaders).toHaveLength(1));
    expect(patchHeaders[0]![ADMIN_SESSION_HEADER]).toBe(WINDOW_TOKEN);
  });
});
