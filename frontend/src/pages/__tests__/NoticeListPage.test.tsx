import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

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

  // ── 사양 SCREEN-030 정합 회귀 가드 ─────────────────────────────────

  it('새_공지_작성은_모달이_아니라_전용_작성_화면으로_이동한다', async () => {
    // given: REVIEWER 가 목록을 연다
    setRole('REVIEWER');
    mock.onGet('/notices').reply(200, mockList());
    renderWithProviders(<NoticeListPage />, { initialEntries: ['/notice'] });
    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });

    // when
    await userEvent.click(screen.getByRole('button', { name: /새 공지 작성/ }));

    // then: 이 화면 안에서 폼이 열리지 않고 /notice/new 로 나간다.
    // 모달이면 작성 화면에 직접 진입할 URL 이 없어 북마크·공유·뒤로가기가 성립하지 않는다.
    expect(navigateMock).toHaveBeenCalledWith('/notice/new');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('번호_컬럼을_두지_않아_DB_PK가_노출되지_않는다', async () => {
    // given: id 5·4 인 공지 2건
    setRole('REVIEWER');
    mock.onGet('/notices').reply(200, mockList());

    // when
    renderWithProviders(<NoticeListPage />, { initialEntries: ['/notice'] });
    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });

    // then: 헤더는 제목/상태/등록일 3열이며 '번호' 컬럼이 없다(사양 SCREEN-030 '번호 컬럼은 없다').
    // 구 구현은 이 자리에 순번도 아닌 DB PK(n.id) 를 그대로 찍어 내부 식별자를 노출했다.
    expect(screen.queryByRole('columnheader', { name: '번호' })).toBeNull();
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).toEqual(['제목', '상태', '등록일']);

    const rows = screen.getAllByRole('row');
    // 첫 데이터 행(고정 공지, id=5)의 셀에 PK 5 가 단독 값으로 찍히지 않는다.
    expect(within(rows[1]).queryByText('5')).toBeNull();
  });

  it('초기화는_입력과_URL_검색조건을_함께_되돌린다', async () => {
    // given: 검색어가 URL 에 적용된 상태로 진입
    setRole('WORKER');
    mock.onGet('/notices').reply(200, mockList());
    renderWithProviders(<NoticeListPage />, {
      initialEntries: ['/notice?keyword=점검&field=TITLE'],
    });
    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });
    // placeholder 는 **마침표까지** 사양 문구 그대로다(SCREEN-030) — 정확 일치로 고정해
    // 마침표가 다시 떨어지면 이 줄이 실패하게 둔다.
    const keywordInput = screen.getByPlaceholderText('검색어를 입력하세요.');
    expect(keywordInput).toHaveValue('점검');

    // when: 초기화를 누른다
    await userEvent.click(screen.getByRole('button', { name: /초기화/ }));

    // then: 입력칸이 비고 조회 파라미터에서도 keyword 가 사라진다.
    // 입력만 비우면 URL 의 keyword 가 남아 "입력은 비었는데 결과는 검색 상태" 인 화면이 된다.
    expect(keywordInput).toHaveValue('');
    await waitFor(() => {
      const lastGet = mock.history.get.at(-1);
      expect(lastGet?.params?.keyword).toBeUndefined();
    });
  });

  it('필터가_비어있으면_초기화_컨트롤을_노출하지_않는다', async () => {
    // given: 검색 조건 없이 진입
    setRole('WORKER');
    mock.onGet('/notices').reply(200, mockList());

    // when
    renderWithProviders(<NoticeListPage />, { initialEntries: ['/notice'] });
    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });

    // then: 되돌릴 것이 없으면 버튼도 없다(공용 필터바 컨벤션 — '필터 활성 시에만 노출').
    expect(screen.queryByRole('button', { name: /초기화/ })).toBeNull();
  });
});
