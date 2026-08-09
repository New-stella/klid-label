import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { NoticeDetailPage } from '@/pages/NoticeDetailPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

// 수정 동선이 모달에서 전용 화면 이동으로 바뀌면서 이동 목적지가 검증 대상이 됐다 —
// 호출마다 새 vi.fn() 을 돌려주면 단언할 대상이 남지 않으므로 고정 mock 을 공유한다.
const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return {
    ...actual,
    useNavigate: () => navigateMock,
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
    navigateMock.mockReset();
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

  // ── 사양 SCREEN-031 정합 회귀 가드 ─────────────────────────────────

  it('수정은_모달이_아니라_전용_수정_화면으로_이동한다', async () => {
    // given: REVIEWER 가 상세를 연다
    setRole('REVIEWER');
    mock.onGet('/notices/5').reply(200, NOTICE_WITH_ATTACH);
    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });
    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });

    // when
    await userEvent.click(screen.getByRole('button', { name: '수정' }));

    // then: 이 화면 안에서 폼이 열리지 않고 /notice/:id/edit 로 나간다.
    // 모달이면 수정 화면에 직접 진입할 URL 이 없고 첨부 관리까지 모달 안에 갇힌다.
    expect(navigateMock).toHaveBeenCalledWith('/notice/5/edit');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByRole('button', { name: '파일 추가' })).toBeNull();
  });

  it('발행제어는_상단_관리액션은_하단으로_분리된다', async () => {
    // given: REVIEWER 가 발행된 공지를 연다
    setRole('REVIEWER');
    mock.onGet('/notices/5').reply(200, NOTICE_WITH_ATTACH);

    // when
    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });
    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });

    // then: 상단 바에는 발행 제어만, 하단 영역에는 수정·삭제만 있다.
    // 하나의 액션 바에 모아 두면 '발행취소' 옆에서 비가역 '삭제' 를 잘못 누르기 쉽다.
    const publishBar = screen.getByTestId('notice-publish-actions');
    const manageBar = screen.getByTestId('notice-manage-actions');

    expect(within(publishBar).getByRole('button', { name: '발행취소' })).toBeInTheDocument();
    expect(within(publishBar).queryByRole('button', { name: '수정' })).toBeNull();
    expect(within(publishBar).queryByRole('button', { name: '삭제' })).toBeNull();

    expect(within(manageBar).getByRole('button', { name: '수정' })).toBeInTheDocument();
    expect(within(manageBar).getByRole('button', { name: '삭제' })).toBeInTheDocument();
    expect(within(manageBar).queryByRole('button', { name: '발행취소' })).toBeNull();
  });

  it('발행일시는_게시_메타에_렌더되지_않는다', async () => {
    // given: pubDt 가 채워진(=발행된) 공지.
    // 그 값은 "최근 발행 시점"이라 발행을 취소하면 비워지고 재발행하면 덮인다 — 최초 발행 이력이
    // 아니므로 게시 메타에 놓으면 이력처럼 오독된다(사양 SCREEN-031 게시 메타 note).
    setRole('REVIEWER');
    mock.onGet('/notices/5').reply(200, NOTICE_WITH_ATTACH);

    // when
    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });
    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });

    // then: 등록은 보이지만 '발행: …' 은 없다
    expect(screen.getByText(/^등록: /)).toBeInTheDocument();
    expect(screen.queryByText(/^발행: /)).toBeNull();
  });

  it('삭제_확인_문구에_대상_공지_제목이_들어간다', async () => {
    // given: REVIEWER 가 공지 상세를 연다
    setRole('REVIEWER');
    mock.onGet('/notices/5').reply(200, NOTICE_WITH_ATTACH);
    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });
    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });

    // when: 삭제를 누른다
    await userEvent.click(screen.getByRole('button', { name: '삭제' }));

    // then: 어느 공지를 지우는지 확인 단계가 말해 준다(비가역 작업 — 고정 문구는 오삭제를 못 거른다)
    expect(
      await screen.findByText('중요 고정 공지 공지를 삭제합니다. 이 작업은 되돌릴 수 없습니다.'),
    ).toBeInTheDocument();
    expect(screen.getByText('공지 삭제')).toBeInTheDocument();
  });

  // 작성자 표시 — 내부 사용자 번호(USER_NO 문자열)가 아니라 표시명을 보여야 한다.
  // BE NoticeResponse 는 regId(원값) + writerName(LS_ACNT_USER.USER_NM) 을 모두 내린다.
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
