import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { NoticeDetailPage } from '@/pages/NoticeDetailPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return {
    ...actual,
    useNavigate: () => vi.fn(),
    useParams: () => ({ id: '5' }),
  };
});

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub: 'u', role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

const NOTICE_WITH_ATTACH = {
  success: true,
  data: {
    id: 5,
    title: '중요 고정 공지',
    content: '본문 내용입니다.',
    pinned: true,
    pubStatus: 'PUBLISHED',
    pubDt: '2026-06-01T10:00:00',
    regId: 'reviewer1',
    regDt: '2026-06-01T09:00:00',
    mdfcnDt: null,
    attachments: [
      { attachSn: 11, fileName: '점검안내.pdf', fileSize: 20480, regDt: '2026-06-01T09:00:00' },
    ],
  },
  message: null,
  errorCode: null,
};

describe('NoticeDetailPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('상세에서_첨부_다운로드_링크_표시', async () => {
    setRole('WORKER');
    mock.onGet('/notices/5').reply(200, NOTICE_WITH_ATTACH);

    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });

    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });
    expect(screen.getByText('점검안내.pdf')).toBeInTheDocument();
    // 다운로드 버튼(파일명 포함) 이 존재
    expect(
      screen.getByRole('button', { name: /점검안내\.pdf/ }),
    ).toBeInTheDocument();
  });

  it('WORKER에게는_수정발행삭제_버튼_미노출', async () => {
    setRole('WORKER');
    mock.onGet('/notices/5').reply(200, NOTICE_WITH_ATTACH);

    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });

    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: '수정' })).toBeNull();
    expect(screen.queryByRole('button', { name: '삭제' })).toBeNull();
    expect(screen.queryByRole('button', { name: '발행취소' })).toBeNull();
  });

  it('REVIEWER에게_수정발행취소삭제_버튼_노출', async () => {
    setRole('REVIEWER');
    mock.onGet('/notices/5').reply(200, NOTICE_WITH_ATTACH);

    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });

    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: '수정' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '삭제' })).toBeInTheDocument();
    // PUBLISHED 상태이므로 발행취소 노출
    expect(screen.getByRole('button', { name: '발행취소' })).toBeInTheDocument();
  });
});
