import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { UserManagePage } from '@/pages/manage/UserManagePage';
import { renderWithProviders } from '@/test/renderWithProviders';
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
