import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

// jsdom 환경에서 konva 가 native canvas 모듈을 요구하므로 mock 으로 대체.
vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { ReviewPage } from '@/pages/ReviewPage';
import { useReviewSelectionStore } from '@/features/review/store/useReviewSelectionStore';
import { useUiStore } from '@/stores/useUiStore';

/**
 * 검수 상세 — 점유 충돌 안내와 재검수 건의 검수 시작(SCREEN-019 · API-013).
 *
 * ★진입 시 자동 검수 시작이 **남의 점유로 거절**될 수 있다. 그 사실을 알리지 않으면 검수자는
 * 「왜 내 이름이 안 뜨지」를 알 길이 없고, 그대로 작업하다 일괄 검수완료 대상에도 담기지 않는다.
 */

const baseReview = {
  id: 10,
  videoId: 1,
  cctvName: 'CCTV-1',
  workerId: 7,
  workerName: '홍길동',
  submittedAt: '2026-05-07T10:00:00Z',
  labelCount: 12,
  needsRecheck: false,
};

describe('검수 상세 — 점유 충돌과 재검수 시작', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useReviewSelectionStore.getState().clear();
    useReviewSelectionStore.getState().setCurrentFrameIdx(0);
    // 알림은 전역 스토어에 쌓인다 — 앞 케이스의 잔재가 건수 단언을 흔들지 않게 비운다.
    useUiStore.setState({ toasts: [] });
    mock.onGet('/reviews/10/issues').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
    mock.onGet('/reviews/1/frames').reply(200, {
      success: true,
      data: { videoId: 1, totalFrames: 0, frames: [] },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
  });

  function renderPage() {
    return renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });
  }

  it('남이_점유_중이면_거절_사실과_그_사람의_이름을_서버_문구로_알린다', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: {
        ...baseReview,
        status: 'REVIEW_PENDING',
        reviewingUserId: 7,
        reviewingUserName: '김검수',
        reviewStartedAt: '2026-05-07T11:00:00Z',
        bulkApprovable: false,
      },
      message: null,
      errorCode: null,
    });

    let startCalls = 0;
    // ★같은 409 에 사유가 셋이다(남의 점유 / 동시 시작 경합 / 받아들일 수 없는 상태) —
    //   그래서 화면은 **서버가 보낸 문장을 그대로** 싣는다. 사유 코드로 문장을 지어내면
    //   결론은 맞고 사유는 거짓인 안내가 된다. 남의 점유 문구에는 점유자 이름이 들어 있다.
    mock.onPost('/reviews/10/start').reply(() => {
      startCalls += 1;
      return [
        409,
        {
          success: false,
          data: { reviewingUserName: '김검수' },
          message: '김검수 님이 검수 중입니다.',
          errorCode: 'CONFLICT',
        },
      ];
    });

    renderPage();

    // ★서버 문장이 **그대로** 헤더에 머무는 안내로 선다(SCREEN-019 §검수 헤더).
    //   사라지는 알림으로 두면 자리를 비웠다 돌아온 사람이 왜 자기 이름이 안 뜨는지 알 수 없다.
    expect(await screen.findByTestId('review-header-claim-conflict')).toHaveTextContent(
      '김검수 님이 검수 중입니다.',
    );

    // ★막히는 것은 검수 시작 하나뿐이다 — 화면 전체가 잠긴 것처럼 보이면 안 된다.
    expect(screen.getByTestId('review-header')).toBeInTheDocument();
    expect(screen.getByTestId('review-action-reject')).toBeInTheDocument();

    // ★거절 뒤에 되풀이 호출하지 않는다 — 자동 호출이 응답마다 다시 돌면 안내가 쌓인다.
    await waitFor(() => expect(startCalls).toBe(1));
    await new Promise((r) => setTimeout(r, 120));
    expect(startCalls).toBe(1);
  });

  it('남이_점유_중이면_헤더가_그_사람의_이름을_보인다', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: {
        ...baseReview,
        status: 'REVIEWING',
        reviewingUserId: 7,
        reviewingUserName: '김검수',
        reviewStartedAt: '2026-05-07T11:00:00Z',
        bulkApprovable: false,
      },
      message: null,
      errorCode: null,
    });

    renderPage();

    expect(await screen.findByTestId('review-header-claim')).toHaveTextContent('김검수 검수 중');
  });

  it('재검수_건은_들어오는_것만으로_잡히지_않고_검수_시작_버튼이_선다', async () => {
    // ★수정 뒤 재검수를 기다리는 영상은 이 화면에 들어오는 것만으로는 잡히지 않는다(SCREEN-019).
    //   자동으로 잡으면 승인 결과를 들여다보기만 하려던 사람이 그 영상을 묶어 버린다.
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: {
        ...baseReview,
        status: 'COMPLETED',
        needsRecheck: true,
        reviewingUserId: null,
        reviewingUserName: null,
        bulkApprovable: false,
      },
      message: null,
      errorCode: null,
    });

    let startCalls = 0;
    mock.onPost('/reviews/10/start').reply(() => {
      startCalls += 1;
      return [200, { success: true, data: { ...baseReview, status: 'COMPLETED' }, message: null, errorCode: null }];
    });

    renderPage();

    // 잡는 길이 화면에 드러난다 — 잡아야 검수 목록의 일괄 검수완료 대상에 담긴다.
    expect(await screen.findByTestId('review-action-start')).toBeInTheDocument();
    // 들어오는 것만으로는 잡히지 않는다.
    await new Promise((r) => setTimeout(r, 120));
    expect(startCalls).toBe(0);
  });

  it('이미_내가_잡은_재검수_건에도_검수_시작_버튼이_남아_점유를_갱신할_수_있다', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: {
        ...baseReview,
        status: 'COMPLETED',
        needsRecheck: true,
        reviewingUserId: 9001,
        reviewingUserName: '나검수',
        reviewStartedAt: '2026-05-07T11:00:00Z',
        // 서버가 「이 건은 네가 잡았고 담을 수 있다」고 말한다.
        bulkApprovable: true,
      },
      message: null,
      errorCode: null,
    });

    renderPage();

    expect(await screen.findByTestId('review-header-claim')).toHaveTextContent('내가 검수 중');
    // ★감추지 않는다(SCREEN-019) — 다시 누르면 거절되지 않고 잡은 시각만 뒤로 밀려,
    //   오래 들여다보는 동안 유예로 풀리는 것을 막는다. 감추면 그 수단이 사라진다.
    expect(screen.getByTestId('review-action-start')).toBeInTheDocument();
  });

  it('점유_표시에_언제부터_보고_있는지가_함께_나온다', async () => {
    // 방금 잡은 것인지 한참 전에 잡은 것인지 알아야 기다릴지 말지를 판단할 수 있다(SCREEN-019).
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: {
        ...baseReview,
        status: 'REVIEWING',
        reviewingUserId: 7,
        reviewingUserName: '김검수',
        reviewStartedAt: '2026-05-07T11:00:00Z',
        bulkApprovable: false,
      },
      message: null,
      errorCode: null,
    });

    renderPage();

    const claim = await screen.findByTestId('review-header-claim');
    expect(claim).toHaveTextContent('김검수 검수 중');
    expect(claim).toHaveTextContent('부터');
  });

  it('검수대기_영상은_종전대로_들어오면_자동으로_잡힌다', async () => {
    // 회귀 가드 — 재검수 분기를 더하면서 기존 자동 시작이 꺼지면 안 된다.
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEW_PENDING', bulkApprovable: false },
      message: null,
      errorCode: null,
    });

    let started = false;
    mock.onPost('/reviews/10/start').reply(() => {
      started = true;
      return [200, { success: true, data: { ...baseReview, status: 'REVIEWING' }, message: null, errorCode: null }];
    });

    renderPage();

    await waitFor(() => expect(started).toBe(true));
  });
});
