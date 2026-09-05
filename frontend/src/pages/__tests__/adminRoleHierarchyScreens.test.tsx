// 회귀 가드 — 관리자가 검수자와 **같은 화면 기능**을 받는다. [@design ROLE-004] [@design AC-125]
//
// 배경: 화면 곳곳이 역할을 «값이 같은가»로 보고 있었다. 서버는 계층으로 관리자를 검수자 자리에
//   통과시키는데 화면만 거짓을 내서, 관리자에게 검수자 기능이 통째로 사라져 있었다. 판정을
//   `@/lib/authz` 한 곳으로 모았고 이 가드가 그 배선이 실제로 화면에 닿는지를 본다.
//
// ★대조군을 반드시 짝으로 둔다. 「관리자에게 보인다」만 두면 판정을 항상-참으로 열어도 초록이라,
//   그 자리가 정말 게이트를 물고 있는지 알 수 없다. 작업자 케이스가 그 축을 증명한다.
//
// ★부정 단언의 픽스처를 비우지 않는다. 목록이 0건이면 「안 보인다」가 데이터가 없어서 참이 되고,
//   게이트를 지워도 통과한다. 모든 케이스가 **같은 비어 있지 않은 응답**을 쓴다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { NoticeDetailPage } from '@/pages/NoticeDetailPage';
import { NoticeListPage } from '@/pages/NoticeListPage';
import { TaskListPage } from '@/pages/TaskListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock, useParams: () => ({ id: '5' }) };
});

/**
 * `null` 은 **역할 미부여**다 — 지어낸 상태가 아니라 스토어가 실제로 만드는 값이다. 토큰은
 * 유효한데 `role` 클레임이 비었거나 우리가 모르는 값이면 토큰 해석기가 `role: null` 로 낮춘다
 * (인증은 살아 있고 권한만 없다).
 */
function setRole(role: 'ADMIN' | 'REVIEWER' | 'WORKER' | null) {
  useAuthStore.setState({
    token: 'dummy-jwt',
    claims: { sub: '9001', role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

function ok<T>(data: T) {
  return { success: true, data, message: null, errorCode: null };
}

function page<T>(content: T[], totalElements = content.length) {
  return ok({ content, totalElements, totalPages: 1, number: 0, size: 20 });
}

/** 공지 2건 — 부정 단언이 「데이터가 없어서」 참이 되지 않도록 항상 비어 있지 않다. */
const NOTICES = [
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
];

const NOTICE_DETAIL = ok({
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
  attachments: [],
});

describe('공지 목록 — 관리자는 검수자와 같은 쓰기 동선을 받는다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    mock.onGet('/notices').reply(200, page(NOTICES));
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function renderList(role: 'ADMIN' | 'REVIEWER' | 'WORKER' | null) {
    setRole(role);
    renderWithProviders(<NoticeListPage />, { initialEntries: ['/notice'] });
    // 목록이 실제로 그려진 뒤에 단언한다 — 로딩 중 스냅샷에 「없다」를 걸면 항상 참이 된다.
    await waitFor(() => {
      expect(screen.getByText('중요 고정 공지')).toBeInTheDocument();
    });
  }

  it('관리자에게_새_공지_작성_버튼이_보인다', async () => {
    await renderList('ADMIN');
    expect(screen.getByRole('button', { name: /새 공지 작성/ })).toBeInTheDocument();
  });

  it('검수자에게도_그대로_보인다', async () => {
    // 계층을 더한 것이지 검수자에게서 뺏은 것이 아니다.
    await renderList('REVIEWER');
    expect(screen.getByRole('button', { name: /새 공지 작성/ })).toBeInTheDocument();
  });

  it('작업자에게는_보이지_않는다', async () => {
    // ★대조군 — 목록은 2건 그려진 상태다(빈 화면이라 참인 것이 아니다).
    await renderList('WORKER');
    expect(screen.getAllByText(/공지$/).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /새 공지 작성/ })).toBeNull();
  });

  it('역할_미부여도_작업자와_같은_결과다', async () => {
    // 이 화면은 역할이 없으면 `?? Role.WORKER` 로 작업자를 채웠다. 그 폴백을 걷어냈는데
    // `roleSatisfies` 가 fail-closed 라 판정 결과는 한 글자도 달라지지 않는다 — 그 동치를
    // 못 박는다(폴백이 없어서 무언가 열리는 일이 없다는 뜻이기도 하다).
    await renderList(null);
    expect(screen.getAllByText(/공지$/).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /새 공지 작성/ })).toBeNull();
  });
});

describe('공지 상세 — 관리자는 발행 제어·수정·삭제를 받는다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    mock.onGet('/notices/5').reply(200, NOTICE_DETAIL);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function renderDetail(role: 'ADMIN' | 'REVIEWER' | 'WORKER' | null) {
    setRole(role);
    renderWithProviders(<NoticeDetailPage />, { initialEntries: ['/notice/5'] });
    await waitFor(() => {
      expect(screen.getByText('본문 내용입니다.')).toBeInTheDocument();
    });
  }

  it('관리자에게_수정삭제_카드와_발행_제어가_보인다', async () => {
    await renderDetail('ADMIN');
    expect(screen.getByTestId('notice-manage-actions')).toBeInTheDocument();
    expect(screen.getByTestId('notice-publish-actions')).toBeInTheDocument();
  });

  it('작업자에게는_둘_다_보이지_않는다', async () => {
    // ★대조군 — 본문은 정상적으로 그려진 상태다.
    await renderDetail('WORKER');
    expect(screen.getByText('본문 내용입니다.')).toBeInTheDocument();
    expect(screen.queryByTestId('notice-manage-actions')).toBeNull();
    expect(screen.queryByTestId('notice-publish-actions')).toBeNull();
  });

  it('역할_미부여도_작업자와_같은_결과다', async () => {
    // 폴백(`?? Role.WORKER`)을 걷어낸 뒤에도 결과가 그대로인지 본다. 본문은 그려진 상태다.
    await renderDetail(null);
    expect(screen.getByText('본문 내용입니다.')).toBeInTheDocument();
    expect(screen.queryByTestId('notice-manage-actions')).toBeNull();
    expect(screen.queryByTestId('notice-publish-actions')).toBeNull();
  });
});

describe('작업 목록 — 관리자는 검수자 축 데이터를 조회한다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    // 두 축 모두 스텁해 둔다 — 한쪽만 두면 「호출하지 않았다」와 「스텁이 없어 실패했다」가
    // 구분되지 않는다.
    mock.onGet('/tasks/board').reply(200, page([], 0));
    mock.onGet('/tasks/board/summary').reply(
      200,
      ok({ total: 0, unassigned: 0, inProgress: 0, reviewPending: 0, completed: 0, rejected: 0 }),
    );
    mock.onGet('/tasks/board/event-types').reply(200, ok({ items: [], truncated: false }));
    mock.onGet('/assignments').reply(200, page([], 0));
    mock.onGet('/assignments/event-types').reply(200, ok({ items: [], truncated: false }));
    mock.onGet('/users').reply(200, page([]));
    // 이벤트유형 표시명 조회 — 스텁하지 않으면 mock adapter 가 실제 네트워크를 시도해
    // 잡음 오류가 나고 타이밍이 흔들린다(이 가드의 관심사 밖).
    mock.onGet('/event-types/labels').reply(200, ok({}));
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function urls() {
    return mock.history.get.map((r) => r.url);
  }

  it('관리자는_검수자_통합_목록을_부르고_작업자_배정목록은_부르지_않는다', async () => {
    // ★이 축은 버튼 유무가 아니라 **어느 창구를 부르는가**라, 화면 문구가 바뀌어도 살아남는다.
    setRole('ADMIN');
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    // 검수자 축 진입이 **끝까지** 돈 뒤에 단언한다 — 첫 요청만 보고 끝내면 「부르지 않았다」가
    // 「아직 안 불렀다」일 수 있어 부정 단언이 조용히 항상-참이 된다.
    await waitFor(() => {
      expect(urls()).toContain('/users');
    });
    expect(urls()).toContain('/tasks/board');
    expect(urls()).toContain('/tasks/board/summary');
    expect(urls()).not.toContain('/assignments');
    // 화면 문구도 검수자 축이다(창구와 표시가 함께 움직이는지 확인).
    expect(screen.getByText('처리 완료된 영상만 표시')).toBeInTheDocument();
  });

  it('작업자는_배정목록을_부르고_검수자_통합_목록은_부르지_않는다', async () => {
    setRole('WORKER');
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(urls()).toContain('/assignments/event-types');
    });
    expect(urls()).toContain('/assignments');
    expect(urls()).not.toContain('/tasks/board');
    expect(screen.getByText('본인에게 배정된 작업만 표시')).toBeInTheDocument();
  });

  it('역할_미부여도_작업자와_같은_창구를_부른다', async () => {
    // 폴백(`?? Role.WORKER`)을 걷어낸 뒤에도 **어느 창구를 부르는가**가 그대로인지 본다 —
    // 버튼 유무보다 강한 축이라 화면 문구가 바뀌어도 살아남는다.
    setRole(null);
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(urls()).toContain('/assignments/event-types');
    });
    expect(urls()).toContain('/assignments');
    expect(urls()).not.toContain('/tasks/board');
    expect(screen.getByText('본인에게 배정된 작업만 표시')).toBeInTheDocument();
  });
});
