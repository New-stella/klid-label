/**
 * 검수 목록 — 제외분 배제·전환 축 회귀 가드.
 * [@design SCREEN-018] [@design API-008] [@design API-138]
 * [@design AC-1124] [@design AC-1126]
 *
 * ★이 화면은 제외·복원을 **수행하지 않는다** — 제외분이 빠진다는 사실과 그 건수를 보여주고
 *   제외분을 열람하는 데까지다(수행하는 자리는 영상 처리 현황이다).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { ReviewListPage } from '@/pages/ReviewListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

function setReviewer() {
  useAuthStore.setState({
    token: 'dummy-jwt',
    claims: { sub: '7', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
  });
}

function ok<T>(data: T) {
  return { success: true, data, message: null, errorCode: null };
}

/** 내가 잡고 있어 고를 수 있는 행 — 체크칸이 실제로 서는 상태를 만든다. */
const MINE = {
  id: 91,
  videoId: 91,
  cctvName: 'CCTV-검수',
  workerId: 3,
  workerName: '김작업',
  submittedAt: '2026-05-07T10:00:00Z',
  labelCount: 5,
  status: 'REVIEW_PENDING',
  needsRecheck: false,
  reviewingUserId: 7,
  reviewingUserName: '나검수',
  bulkApprovable: true,
};

function mockReviews(mock: MockAdapter, excludedCount = 0) {
  mock.onGet('/reviews').reply(200,
    ok({
      content: [MINE],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
      bulkApproveLimit: 50,
    }),
  );
  // ★「제외됨 N건」은 **집계 창구**가 싣는다(목록 조회는 싣지 않는다 — 진실원을 둘로 두지 않는다).
  mock.onGet('/reviews/summary').reply(200,
    ok({ total: 1, pending: 1, inReview: 0, approved: 0, rejected: 0, excludedCount }),
  );
}

/** 마지막 목록 요청의 질의 항목 — 「무엇을 빼고 보냈는가」를 본다. */
function lastReviewParams(mock: MockAdapter): Record<string, unknown> {
  const calls = mock.history.get.filter((c) => c.url === '/reviews');
  return (calls.at(-1)?.params ?? {}) as Record<string, unknown>;
}

describe('검수 목록 — 제외분 배제', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    setReviewer();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.clearAllMocks();
  });

  it('★제외됨이_0건이어도_표시된다', async () => {
    mockReviews(mock, 0);

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });

    // ★★**집계가 도착한 뒤**를 관측한다. 이 표시는 첫 렌더부터 자리에 있고 그때는 아직 값을
    //   못 받아 「제외됨 0건」으로 그려진다 — 기다리지 않으면 이 케이스는 **서버 값을 한 번도
    //   읽지 않고도 통과**한다. 값을 받았는지는 버튼이 눌리는지로 가른다.
    const toggle = await screen.findByTestId('excluded-count-toggle');
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).toHaveTextContent('제외됨 0건');
  });

  it('★제외됨을_누르면_검수_상태_축만_빼고_전환한다', async () => {
    // 그 숫자를 주는 집계 창구가 검수 상태 축을 반영하지 않고 세므로, 상태를 그대로 둔 채
    // 전환하면 결과가 누른 숫자보다 적어진다. 검색어는 그대로 가야 결과가 넓어지지도 않는다.
    mockReviews(mock, 3);

    renderWithProviders(<ReviewListPage />, {
      initialEntries: ['/review?status=REJECTED&q=강남&sort=submittedAt,asc&page=0&size=20'],
    });

    await waitFor(() => expect(lastReviewParams(mock).status).toBe('REJECTED'));

    await userEvent.click(screen.getByTestId('excluded-count-toggle'));

    await waitFor(() => expect(lastReviewParams(mock).excludedOnly).toBe(true));
    const params = lastReviewParams(mock);
    expect(params.status).toBeUndefined();
    expect(params.q).toBe('강남');
  });

  it('기본_목록에서는_제외분만_보기_항목을_싣지_않는다', async () => {
    mockReviews(mock, 0);

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });
    await screen.findByText('CCTV-검수');

    expect(lastReviewParams(mock)).not.toHaveProperty('excludedOnly');
  });

  it('★제외분_보기에서는_검수_동작과_선택_체크칸이_사라진다', async () => {
    // 제외된 영상을 검수 대상으로 되돌릴 수 있으면 제외가 무의미해진다.
    mockReviews(mock, 1);

    renderWithProviders(<ReviewListPage />, {
      initialEntries: ['/review?excludedOnly=true&status=ALL&sort=submittedAt,asc&page=0&size=20'],
    });

    await screen.findByText('CCTV-검수');

    expect(screen.queryByTestId('review-select-91')).not.toBeInTheDocument();
    expect(screen.queryByTestId('review-select-all')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /검수시작/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /이어서 검수/ })).not.toBeInTheDocument();
    expect(screen.queryByTestId('bulk-approve-bar')).not.toBeInTheDocument();
    // 지금 보는 것이 기본 목록이 아니라는 사실과 되돌아갈 수단을 함께 세운다.
    expect(screen.getByTestId('excluded-only-notice')).toBeInTheDocument();
    expect(screen.getByTestId('excluded-only-exit')).toBeInTheDocument();
  });

  it('기본_목록에서는_선택_체크칸과_검수_동작이_그대로_있다', async () => {
    // ★부재 단언만 두면 「항상 사라짐」으로 굳어도 통과한다 — 남아야 하는 쪽을 짝으로 둔다.
    mockReviews(mock, 1);

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });

    expect(await screen.findByTestId('review-select-91')).toBeInTheDocument();
    expect(screen.getByTestId('review-select-all')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /검수시작/ })).toBeInTheDocument();
  });

  it('★이_화면에는_제외_복원_동작이_없다', async () => {
    // 제외·복원을 수행하는 자리는 영상 처리 현황 하나다(AC-1126).
    mockReviews(mock, 1);

    renderWithProviders(<ReviewListPage />, {
      initialEntries: ['/review?excludedOnly=true&status=ALL&sort=submittedAt,asc&page=0&size=20'],
    });
    await screen.findByText('CCTV-검수');

    expect(screen.queryByRole('button', { name: /^제외$/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /복원/ })).not.toBeInTheDocument();
  });
});
