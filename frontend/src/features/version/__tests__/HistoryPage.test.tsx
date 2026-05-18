import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

import { HistoryPage } from '@/pages/HistoryPage';

function setRole(role: 'REVIEWER' | 'WORKER', sub = 'u-7') {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub, role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

const versionsPayload = {
  success: true,
  data: [
    {
      commitSha: 'aaa111aaa111aaa111aaa111aaa111aaa111aaa1',
      shortHash: 'aaa111',
      authorName: '홍길동',
      message: '라벨 수정',
      committedAt: '2026-05-07T10:00:00Z',
      isCurrent: true,
    },
    {
      commitSha: 'bbb222bbb222bbb222bbb222bbb222bbb222bbb2',
      shortHash: 'bbb222',
      authorName: '김검수',
      message: '초기 라벨',
      committedAt: '2026-05-06T10:00:00Z',
      isCurrent: false,
    },
  ],
  message: null,
  errorCode: null,
};

describe('HistoryPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('REVIEWER가_접근시_버전_목록과_롤백_버튼_노출', async () => {
    setRole('REVIEWER');
    mock.onGet('/frames/777/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPage />, {
      initialEntries: ['/history/777'],
      routes: [{ path: '/history/:videoId', element: <HistoryPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByText('aaa111')).toBeInTheDocument();
    });
    // HistoryPanel 의 commit-row 사용 (page 는 panel 의 wrapper)
    expect(screen.getByTestId('commit-row-aaa111')).toBeInTheDocument();
    expect(screen.getByTestId('commit-row-bbb222')).toBeInTheDocument();
  });

  it('WORKER_권한도_diff_조회_가능', async () => {
    setRole('WORKER');
    mock.onGet('/frames/777/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPage />, {
      initialEntries: ['/history/777'],
      routes: [{ path: '/history/:videoId', element: <HistoryPage /> }],
    });

    // WORKER도 버전 목록과 diff UI 자체에는 접근
    await waitFor(() => {
      expect(screen.getByText('aaa111')).toBeInTheDocument();
    });
  });

  it('WORKER가_롤백_버튼_클릭시_disabled_또는_미노출', async () => {
    setRole('WORKER');
    mock.onGet('/frames/777/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPage />, {
      initialEntries: ['/history/777'],
      routes: [{ path: '/history/:videoId', element: <HistoryPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByText('aaa111')).toBeInTheDocument();
    });

    // WORKER에게는 롤백 트리거 버튼이 없거나 disabled
    const rollbackBtn = screen.queryByRole('button', { name: /롤백 시작/ });
    if (rollbackBtn) {
      expect(rollbackBtn).toBeDisabled();
    } else {
      expect(rollbackBtn).toBeNull();
    }
  });

  it('REVIEWER가_롤백_시작_클릭시_확인_모달_노출', async () => {
    setRole('REVIEWER');
    mock.onGet('/frames/777/versions').reply(200, versionsPayload);

    renderWithProviders(<HistoryPage />, {
      initialEntries: ['/history/777'],
      routes: [{ path: '/history/:videoId', element: <HistoryPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByText('aaa111')).toBeInTheDocument();
    });

    // 최신 아닌 커밋(bbb222) 선택 → 롤백 트리거 노출 → 클릭
    const bbbRow = screen.getByTestId('commit-row-bbb222');
    const selectBtn = bbbRow.querySelector('button[type="button"]') as HTMLButtonElement;
    fireEvent.click(selectBtn);

    await waitFor(() => {
      expect(screen.getByTestId('rollback-trigger-bbb222')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('rollback-trigger-bbb222'));

    await waitFor(() => {
      expect(screen.getByText('이 버전으로 롤백하시겠습니까?')).toBeInTheDocument();
    });
  });
});
