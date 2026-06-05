import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { NoticeListPage } from '@/pages/NoticeListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return { ...actual, useNavigate: () => navigateMock };
});

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub: 'u', role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

function mockList() {
  // 고정글이 BE 정렬에 의해 content 상단에 온다는 전제 (고정 우선 → 최신순).
  return {
    success: true,
    data: {
      content: [
        {
          id: 5,
          title: '중요 고정 공지',
          pinned: true,
          pubStatus: 'PUBLISHED',
          pubDt: '2026-06-01T10:00:00',
          regDt: '2026-06-01T09:00:00',
        },
        {
          id: 4,
          title: '일반 공지',
          pinned: false,
          pubStatus: 'PUBLISHED',
          pubDt: '2026-05-20T10:00:00',
          regDt: '2026-05-20T09:00:00',
        },
      ],
      totalElements: 2,
      totalPages: 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  };
}

describe('NoticeListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('목록_조회시_고정글이_상단에_표시됨', async () => {
    setRole('WORKER');
    mock.onGet('/notices').reply(200, mockList());

    renderWithProviders(<NoticeListPage />, { initialEntries: ['/notice'] });

    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });

    // 첫 데이터 행에 고정 배지 + 고정 공지 제목이 위치
    const rows = screen.getAllByRole('row');
    // rows[0] 은 thead. rows[1] 이 첫 데이터 행 → 고정 공지여야 함.
    expect(rows[1]).toHaveTextContent('고정');
    expect(rows[1]).toHaveTextContent('중요 고정 공지');
  });

  it('REVIEWER에게만_작성버튼_노출', async () => {
    setRole('REVIEWER');
    mock.onGet('/notices').reply(200, mockList());

    renderWithProviders(<NoticeListPage />, { initialEntries: ['/notice'] });

    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /새 공지 작성/ })).toBeInTheDocument();
  });

  it('WORKER에게는_작성수정버튼_미노출', async () => {
    setRole('WORKER');
    mock.onGet('/notices').reply(200, mockList());

    renderWithProviders(<NoticeListPage />, { initialEntries: ['/notice'] });

    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /새 공지 작성/ })).toBeNull();
    // WORKER 는 상태 컬럼(발행/작성중)도 보지 않음
    expect(screen.queryByText('작성중')).toBeNull();
  });
});
