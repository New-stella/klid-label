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
    token: 'tok',
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
});
