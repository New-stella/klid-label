import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { UserManagePage } from '@/pages/manage/UserManagePage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';
import { useAuthStore } from '@/stores/useAuthStore';

function setReviewer() {
  useAuthStore.setState({
    token: 'dummy-token',
    claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
  });
}

describe('UserManagePage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setReviewer();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('사용자_관리_검색_필터_동작', async () => {
    mock.onGet('/users').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 7,
            loginId: 'hong',
            name: '홍길동',
            role: 'WORKER',
            active: true,
            createdAt: '2026-05-01T00:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    await waitFor(() => {
      expect(screen.getByText('홍길동')).toBeInTheDocument();
    });

    const input = screen.getByLabelText('검색');
    await user.type(input, '홍');
    await user.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() => {
      const last = mock.history.get[mock.history.get.length - 1];
      expect(last?.params).toMatchObject({ keyword: '홍' });
    });
  });

  it('역할_필터는_클라이언트_필터가_아니라_서버_파라미터로_전송된다', async () => {
    // given: 구 버그 — roleFilter 가 컴포넌트 로컬 state 로만 존재해 현재 페이지(20건)
    // 안에서만 걸러졌다("CLAUDE.md 목록 필터 정책" 위반 — 전체 기준 서버 필터여야 한다).
    // BE GET /v1/users 는 role 쿼리 파라미터를 이미 지원한다.
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    await waitFor(() => {
      expect(mock.history.get.length).toBeGreaterThan(0);
    });

    await user.click(screen.getByLabelText('역할 필터'));
    await user.click(await screen.findByRole('option', { name: '작업자' }));

    // then: role=WORKER 가 서버 요청 파라미터로 전송된다
    await waitFor(() => {
      const last = mock.history.get[mock.history.get.length - 1];
      expect(last?.params).toMatchObject({ role: 'WORKER' });
    });
  });

  it('상태(활성_비활성)_필터_컨트롤은_존재하지_않는다', async () => {
    // given: 사양(SCREEN-024) — "상태 필터는 두지 않는다. 활성 상태는 관제서버 소유값"
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    await waitFor(() => {
      expect(mock.history.get.length).toBeGreaterThan(0);
    });

    expect(screen.queryByLabelText('상태 필터')).not.toBeInTheDocument();
  });

  // ── 사양 SCREEN-024 정합 회귀 가드 ─────────────────────────────────

  /** 역할 미배정(BE `UserSummaryResponse.from` 이 role=null 로 내려보내는) 사용자 1건. */
  function stubUnassignedUser() {
    mock.onGet('/users').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 9,
            loginId: 'newbie',
            name: '신규사용자',
            role: null,
            active: true,
            createdAt: '2026-05-01T00:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
  }

  it('역할이_없는_사용자는_미배정으로_표시된다', async () => {
    // given: 관제 인계 키에 역할 클레임이 없는 자동등록 사용자(role=null).
    // 구 구현은 이 null 을 그대로 흘려보내 라벨 없는 빈 회색 배지를 그렸다 —
    // "역할이 없다"와 "값을 못 읽었다"가 화면에서 구분되지 않았다.
    stubUnassignedUser();

    // when
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    // then
    const badge = await screen.findByTestId('user-role-unassigned-9');
    expect(badge).toHaveTextContent('미배정');
  });

  it('미배정_사용자_수정모달은_안내와_함께_저장을_잠근다', async () => {
    // given
    stubUnassignedUser();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    await screen.findByTestId('user-role-unassigned-9');

    // when: 수정 모달을 연다
    await user.click(screen.getByRole('button', { name: '수정' }));

    // then: 임의 기본값(WORKER)을 미리 채우지 않는다 — 그러면 사용자가 고른 적 없는 역할이
    // 저장될 수 있다. 선택 전까지 저장은 잠기고 왜 잠겼는지 안내가 뜬다.
    expect(
      await screen.findByTestId('edit-user-unassigned-notice'),
    ).toHaveTextContent('아직 역할이 배정되지 않은 사용자입니다');
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  // ── 사양 SCREEN-024 회귀 가드: "역할을 선택하지 않았거나 원래 값과 같으면 비활성화된다" ──
  // 구 구현은 **미선택만** 막아, 아무것도 바꾸지 않은 채 저장을 눌러 PATCH 없는 빈 왕복을
  // 만들 수 있었다(요청은 나가지 않고 모달만 닫혀, 눌렀는데 아무 일도 없는 것처럼 보인다).

  /** 역할이 이미 배정된(WORKER) 사용자 1건 — '원래 값과 같음' 축을 보려면 초기값이 있어야 한다. */
  function stubAssignedUser() {
    mock.onGet('/users').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 11,
            loginId: 'worker1',
            name: '김작업',
            role: 'WORKER',
            active: true,
            createdAt: '2026-05-01T00:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
  }

  /** 수정 모달을 열고 그 안의 역할 select 트리거를 돌려준다(목록 필터의 select 와 구분). */
  async function openEditModal(user: UserEvent): Promise<HTMLElement> {
    await user.click(await screen.findByRole('button', { name: '수정' }));
    const dialog = await screen.findByRole('dialog');
    return within(dialog).getByRole('combobox');
  }

  it('원래_역할_그대로면_저장이_잠기고_사유를_밝힌다', async () => {
    // given: 역할이 이미 WORKER 인 사용자
    stubAssignedUser();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    // when: 아무것도 바꾸지 않고 모달만 연다
    await openEditModal(user);

    // then: 저장은 잠기고 **왜 잠겼는지** 화면이 말한다(이유 없이 잠긴 버튼은 고장으로 읽힌다)
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(screen.getByTestId('edit-user-unchanged-notice')).toHaveTextContent(
      '변경된 내용이 없습니다',
    );
  });

  it('다른_역할로_바꾸면_저장이_풀리고_안내가_사라진다', async () => {
    // given
    stubAssignedUser();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    const trigger = await openEditModal(user);

    // when: WORKER → REVIEWER
    await selectRadixOption(user, trigger, '검수자');

    // then
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '저장' })).toBeEnabled();
    });
    expect(screen.queryByTestId('edit-user-unchanged-notice')).toBeNull();
  });

  it('바꿨다가_원래_역할로_되돌리면_저장이_다시_잠긴다', async () => {
    // given: 되돌림까지 봐야 판정이 "한 번이라도 건드렸는가"가 아니라 "지금 값이 원래와
    // 같은가"임이 고정된다(dirty 플래그로 구현하면 이 케이스에서 저장이 열린 채 남는다).
    stubAssignedUser();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    const trigger = await openEditModal(user);

    await selectRadixOption(user, trigger, '검수자');
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '저장' })).toBeEnabled();
    });

    // when: 원래 값(작업자)으로 되돌린다
    await selectRadixOption(user, trigger, '작업자');

    // then
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    });
    expect(screen.getByTestId('edit-user-unchanged-notice')).toBeInTheDocument();
  });

  it('미선택_차단은_그대로_동작하고_변경없음_안내와_겹치지_않는다', async () => {
    // given: 미배정 사용자 — 두 잠금 사유가 동시에 뜨면 사용자는 무엇을 해야 할지 모른다.
    stubUnassignedUser();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    // when
    await openEditModal(user);

    // then: 미선택 사유만 뜬다
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(screen.getByTestId('edit-user-unassigned-notice')).toBeInTheDocument();
    expect(screen.queryByTestId('edit-user-unchanged-notice')).toBeNull();
  });

  it('헤더_부제는_동적_카운트가_아니라_고정_문구다', async () => {
    // given: 사양 — "부제는 정적 텍스트이며 전체 사용자 수 등 동적 수치는 표시하지 않는다".
    // 동적 카운트는 로딩 중 '전체 0명'이 사실처럼 읽히는 문제가 있었다.
    stubUnassignedUser();

    // when
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    await screen.findByTestId('user-role-unassigned-9');

    // then
    expect(screen.getByText('시스템 사용자 계정을 관리합니다.')).toBeInTheDocument();
    expect(screen.queryByText(/전체 \d+명/)).toBeNull();
  });
});
