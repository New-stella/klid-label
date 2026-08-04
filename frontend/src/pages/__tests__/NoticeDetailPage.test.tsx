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
    writerName: null,
    regDt: '2026-06-01T09:00:00',
    mdfcnDt: null,
    attachments: [
      { attachSn: 11, fileName: '점검안내.pdf', fileSize: 20480, regDt: '2026-06-01T09:00:00' },
    ],
  },
  message: null,
  errorCode: null,
};

/** 작성자 축만 바꾼 상세 응답 (BE NoticeResponse: regId 원값 + writerName 표시명). */
function noticeWith(writerName: string | null, regId: string | null) {
  return {
    ...NOTICE_WITH_ATTACH,
    data: { ...NOTICE_WITH_ATTACH.data, writerName, regId },
  };
}

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

  // 작성자 표시 — 내부 사용자 번호(USER_NO 문자열)가 아니라 표시명을 보여야 한다.
  // BE NoticeResponse 는 regId(원값) + writerName(MNG_ACCT_USER.USER_NM) 을 모두 내린다.
  it('작성자는_표시명으로_노출된다', async () => {
    // given: 사용자 마스터에서 이름이 해석된 공지
    setRole('WORKER');
    mock.onGet('/notices/5').reply(200, noticeWith('홍길동', '1024'));

    // when
    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });

    // then: 이름이 보이고 내부 번호는 작성자 자리에 노출되지 않는다
    await waitFor(() => {
      expect(screen.getByText('작성자: 홍길동')).toBeInTheDocument();
    });
    expect(screen.queryByText('작성자: 1024')).toBeNull();
  });

  it('표시명이_null이면_기존_regId로_폴백한다', async () => {
    // given: 레거시 행·탈퇴 계정 등으로 이름 해석 실패 (writerName=null)
    setRole('WORKER');
    mock.onGet('/notices/5').reply(200, noticeWith(null, 'reviewer1'));

    // when
    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });

    // then: 작성자가 통째로 사라지지 않고 원값으로 표시된다
    await waitFor(() => {
      expect(screen.getByText('작성자: reviewer1')).toBeInTheDocument();
    });
  });

  it('표시명이_공백문자뿐이면_기존_regId로_폴백한다', async () => {
    // given: 이름이 빈 문자열/공백만 (null 만 보면 빈칸이 그대로 표시된다)
    setRole('WORKER');
    mock.onGet('/notices/5').reply(200, noticeWith('   ', 'reviewer1'));

    // when
    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });

    // then
    await waitFor(() => {
      expect(screen.getByText('작성자: reviewer1')).toBeInTheDocument();
    });
  });

  it('표시명과_regId가_모두_없으면_작성자를_표시하지_않는다', async () => {
    // given
    setRole('WORKER');
    mock.onGet('/notices/5').reply(200, noticeWith(null, null));

    // when
    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });

    // then: 기존 미표시 동작 유지 (빈 "작성자: " 라벨을 남기지 않는다)
    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });
    expect(screen.queryByText(/^작성자:/)).toBeNull();
  });
});
