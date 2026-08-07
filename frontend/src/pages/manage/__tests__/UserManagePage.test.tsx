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
});
