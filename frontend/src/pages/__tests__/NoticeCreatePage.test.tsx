import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { NoticeCreatePage } from '@/pages/NoticeCreatePage';
import { renderWithProviders } from '@/test/renderWithProviders';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return { ...actual, useNavigate: () => navigateMock };
});

function renderCreatePage() {
  return renderWithProviders(<div />, {
    initialEntries: ['/notice/new'],
    routes: [{ path: '/notice/new', element: <NoticeCreatePage /> }],
  });
}

describe('NoticeCreatePage (공지 작성 전용 화면)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    // 이 화면은 role 을 직접 읽지 않는다 — REVIEWER 제한은 라우트 가드가 담당하며
    // 그 배선은 router/__tests__/noticeGuard.test.tsx 가 별도로 고정한다.
  });

  afterEach(() => {
    mock.restore();
  });

  it('작성_화면은_전용_URL로_직접_진입된다', () => {
    // given/when: 목록을 거치지 않고 /notice/new 로 바로 들어온다.
    renderCreatePage();

    // then: 모달이 아니라 페이지가 뜬다 — 북마크·공유·뒤로가기가 성립하는 진입점이다.
    expect(screen.getByRole('heading', { name: '새 공지 작성' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /뒤로 가기/ })).toBeInTheDocument();
  });

  it('★뒤로가기는_라벨이_보이는_텍스트_버튼이다_수정화면과_동일_형태', () => {
    // given: 확정 디자인은 작성(SCREEN-036 `.back-btn`)·수정(SCREEN-037 `.btn-ghost`) 모두
    //   "투명 테두리 **텍스트** 버튼 + 화살표 아이콘"으로 규정한다. 이 화면만 아이콘 전용
    //   정사각 버튼이라 두 화면의 뒤로가기 형태가 갈려 있었다.
    renderCreatePage();

    // when / then: 접근명이 aria-label 이 아니라 **눈에 보이는 텍스트**에서 나온다.
    const back = screen.getByRole('button', { name: /뒤로 가기/ });
    expect(back).not.toHaveAttribute('aria-label');
    expect(back.textContent).toContain('뒤로 가기');
  });

  it('작성_화면에는_첨부_영역이_없다_게시글_id_미발급', () => {
    // 게시글 id 가 발급되기 전이라 업로드 대상이 없다. 첨부는 저장 후 수정 화면에서 추가한다.
    renderCreatePage();

    expect(screen.queryByLabelText('첨부파일 선택')).toBeNull();
    expect(screen.queryByRole('button', { name: '파일 추가' })).toBeNull();
  });

  it('제목_미입력이면_검증_오류로_저장되지_않는다', async () => {
    renderCreatePage();

    await userEvent.type(screen.getByLabelText(/내용/), '본문 내용');
    await userEvent.click(screen.getByRole('button', { name: '작성' }));

    expect(await screen.findByText(/제목은 필수/)).toBeInTheDocument();
    expect(mock.history.post).toHaveLength(0);
  });

  it('저장_성공시_생성된_게시글_상세로_이동한다', async () => {
    // given: 생성 API 가 새 게시글 id 를 돌려준다.
    mock.onPost('/notices').reply(201, {
      success: true,
      data: {
        id: 9,
        title: '점검 안내',
        content: '02:00 점검',
        pinned: false,
        pubStatus: 'DRAFT',
        pubDt: null,
        regId: 'u',
        writerName: null,
        regDt: '2026-06-01T00:00:00',
        mdfcnDt: null,
        attachments: [],
      },
      message: null,
      errorCode: null,
    });
    renderCreatePage();

    // when
    await userEvent.type(screen.getByLabelText(/제목/), '점검 안내');
    await userEvent.type(screen.getByLabelText(/내용/), '02:00 점검');
    await userEvent.click(screen.getByRole('button', { name: '작성' }));

    // then: 목록이 아니라 방금 만든 글의 상세로 간다(사양 SCREEN-036 '작성' note).
    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/notice/9', { replace: true });
    });
  });

  it('취소하면_목록_화면으로_돌아간다', async () => {
    renderCreatePage();

    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(navigateMock).toHaveBeenCalledWith('/notice');
  });
});
