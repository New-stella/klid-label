// SCREEN-019 §상단 프레임 이동 바 — 검수 상세 화면 회귀 가드.
//
// 사양: "헤더 바로 아래에 위치하는 별도 상단바. 처음/이전/프레임 번호 입력/다음/마지막 프레임
//        이동 컨트롤과 슬라이더로 구성되며, 현재 프레임 위치(Frame N/total)를 이 영역에서 표시한다."
//
// 이 파일이 지키는 것
//  ① 상단바가 **헤더 바로 아래**에 있고, 프레임 위치 표시가 **그 안**에 있다.
//  ② 헤더에는 프레임 위치 표시가 없다(사양 §검수 헤더의 `[폐기] Frame N/total`).
//     — 위치 표시가 두 곳에 있으면 어느 쪽이 진실인지 갈린다.
//  ③ 다섯 진입점(처음·이전·다음·마지막·번호 입력·슬라이더)이 **모두 같은 이동 경로**로 수렴한다.
//     화면의 다른 이동 표면(캔버스 위 썸네일 스트립)도 같은 상태를 본다.
//  ④ 경계 — 첫/마지막 프레임 비활성, 범위 밖 번호 거부, 프레임 0건.
//
// ⚠ 위치 표시는 `현재 번호 / 전체 개수`(번호 입력칸 + 총 개수)로 나타낸다. 사양 본문의
//   `Frame N/total` 은 표기 예시이며, 라벨링 화면(SCREEN-005 §캔버스 상단 옵션바 —
//   "현재 프레임 번호·전체 프레임 수 표시를 함께 둔다")과 같은 컨트롤을 공유하므로 표기도 같다.

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';

// jsdom 환경에서 konva 가 native canvas 모듈을 요구하므로 mock 으로 대체.
vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    // eslint-disable-next-line react/display-name, @typescript-eslint/no-explicit-any
    return ({ children, image: _image, ...rest }: any) =>
      React.createElement('div', { 'data-konva': name, ...rest }, children);
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
    Group: passthrough('Group'),
  };
});

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { ReviewPage } from '@/pages/ReviewPage';
import { useReviewSelectionStore } from '@/features/review/store/useReviewSelectionStore';

const baseReview = {
  id: 10,
  videoId: 1,
  cctvName: 'CCTV-1',
  workerId: 7,
  workerName: '홍길동',
  submittedAt: '2026-05-07T10:00:00Z',
  labelCount: 0,
  status: 'REVIEWING',
};

function frameFixture(count: number) {
  return Array.from({ length: count }, (_, i) => ({
    srcSn: 100 + i,
    frameNo: i,
    imageUrl: `/f/${100 + i}.jpg`,
    labels: [],
  }));
}

beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

function renderReviewPage() {
  return renderWithProviders(<ReviewPage />, {
    initialEntries: ['/review/10'],
    routes: [{ path: '/review/:id', element: <ReviewPage /> }],
  });
}

/**
 * 프레임 목록 로드가 끝날 때까지 기다린 뒤 상단바 안의 주요 컨트롤을 돌려준다.
 * ⚠ 상단바는 로드 전에도 렌더되므로(자리 유지), 로드 완료를 기다리지 않으면 프레임 수 0 인
 *   과도기 상태를 검증하게 된다.
 */
async function navBar() {
  const bar = await screen.findByTestId('review-frame-nav-bar');
  await waitFor(() => expect(screen.queryByTestId('review-frames-loading')).toBeNull());
  const scoped = within(bar);
  return {
    bar,
    numberInput: scoped.getByTestId('frame-number-input') as HTMLInputElement,
    totalCount: scoped.getByTestId('frame-total-count'),
    slider: scoped.getByTestId('frame-position-slider') as HTMLInputElement,
    first: scoped.getByRole('button', { name: '처음 프레임' }),
    prev: scoped.getByRole('button', { name: '이전 프레임' }),
    next: scoped.getByRole('button', { name: '다음 프레임' }),
    last: scoped.getByRole('button', { name: '마지막 프레임' }),
  };
}

describe('SCREEN-019 상단 프레임 이동 바', () => {
  let mock: MockAdapter;

  function mockApis(frameCount: number) {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: baseReview,
      message: null,
      errorCode: null,
    });
    mock.onGet('/reviews/10/issues').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos/1/issues').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
    mock.onGet('/reviews/1/frames').reply(200, {
      success: true,
      data: {
        videoId: 1,
        totalFrames: frameCount,
        frames: frameFixture(frameCount),
      },
      message: null,
      errorCode: null,
    });
  }

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useReviewSelectionStore.getState().clear();
    useReviewSelectionStore.getState().setCurrentFrameIdx(0);
  });

  afterEach(() => {
    mock.restore();
  });

  // ── ① 위치 · ② 헤더 비노출 ────────────────────────────────────────
  it('상단바는_헤더_바로_아래에_있고_프레임_위치_표시가_그_안에_있다', async () => {
    mockApis(4);
    renderReviewPage();

    const { bar, numberInput, totalCount } = await navBar();
    const page = screen.getByTestId('review-page');
    const header = screen.getByTestId('review-header');

    // "헤더 바로 아래" — 페이지 그리드에서 헤더를 담은 칸의 바로 다음 형제가 상단바다.
    const headerCell = header.closest('[data-testid="review-page"] > *');
    expect(headerCell).not.toBeNull();
    expect(headerCell!.parentElement).toBe(page);
    expect(headerCell!.nextElementSibling).toBe(bar);

    // 현재 프레임 위치(현재 번호 / 전체 개수)가 상단바 안에 있다.
    expect(numberInput.value).toBe('1');
    expect(totalCount.textContent).toContain('4');
    expect(bar).toContainElement(numberInput);
    expect(bar).toContainElement(totalCount);
  });

  it('헤더에는_프레임_위치_표시가_없다', async () => {
    mockApis(4);
    renderReviewPage();

    const { numberInput, totalCount, slider } = await navBar();
    const header = screen.getByTestId('review-header');

    expect(within(header).queryByTestId('review-header-frame-counter')).toBeNull();
    expect(within(header).queryByText(/Frame\s*\d+\s*\/\s*\d+/)).toBeNull();
    // 이동 컨트롤·위치 표시가 헤더로 되돌아오지 않았는지 직접 확인한다.
    expect(header).not.toContainElement(numberInput);
    expect(header).not.toContainElement(totalCount);
    expect(header).not.toContainElement(slider);
    expect(within(header).queryByRole('group', { name: '프레임 이동' })).toBeNull();
  });

  // ── ③ 단일 이동 경로 ─────────────────────────────────────────────
  it('처음_이전_다음_마지막_번호입력_슬라이더가_모두_같은_이동_경로로_수렴한다', async () => {
    mockApis(4);
    renderReviewPage();

    const c = await navBar();
    // 캔버스 위 썸네일 스트립도 같은 상태를 본다 — 표면이 갈리지 않는지 함께 확인한다.
    const counter = () => screen.getByTestId('frame-timeline-counter').textContent;

    fireEvent.click(c.next);
    await waitFor(() => expect(c.numberInput.value).toBe('2'));
    expect(counter()).toContain('2 / 4');

    fireEvent.click(c.last);
    await waitFor(() => expect(c.numberInput.value).toBe('4'));
    expect(counter()).toContain('4 / 4');

    fireEvent.click(c.prev);
    await waitFor(() => expect(c.numberInput.value).toBe('3'));

    fireEvent.click(c.first);
    await waitFor(() => expect(c.numberInput.value).toBe('1'));
    expect(counter()).toContain('1 / 4');

    // 번호 직접 입력(Enter) — 같은 경로.
    fireEvent.change(c.numberInput, { target: { value: '3' } });
    fireEvent.keyDown(c.numberInput, { key: 'Enter' });
    await waitFor(() => expect(c.numberInput.value).toBe('3'));
    expect(counter()).toContain('3 / 4');

    // 슬라이더 — 같은 경로(놓는 순간 대기분 flush).
    fireEvent.change(c.slider, { target: { value: '1' } });
    fireEvent.mouseUp(c.slider);
    await waitFor(() => expect(c.numberInput.value).toBe('2'));
    expect(counter()).toContain('2 / 4');
  });

  // ── ④ 경계 ──────────────────────────────────────────────────────
  it('첫_프레임에서는_처음_이전이_비활성이다', async () => {
    mockApis(4);
    renderReviewPage();

    const c = await navBar();
    expect(c.first).toBeDisabled();
    expect(c.prev).toBeDisabled();
    expect(c.next).toBeEnabled();
    expect(c.last).toBeEnabled();
  });

  it('마지막_프레임에서는_다음_마지막이_비활성이다', async () => {
    mockApis(4);
    renderReviewPage();

    const c = await navBar();
    fireEvent.click(c.last);

    await waitFor(() => expect(c.next).toBeDisabled());
    expect(c.last).toBeDisabled();
    expect(c.prev).toBeEnabled();
    expect(c.first).toBeEnabled();
  });

  it('범위_밖_번호_입력은_거부되고_현재_번호로_되돌린다', async () => {
    mockApis(4);
    renderReviewPage();

    const c = await navBar();

    fireEvent.change(c.numberInput, { target: { value: '9' } });
    fireEvent.keyDown(c.numberInput, { key: 'Enter' });
    await waitFor(() => expect(c.numberInput.value).toBe('1'));
    expect(screen.getByTestId('frame-timeline-counter').textContent).toContain('1 / 4');

    fireEvent.change(c.numberInput, { target: { value: '0' } });
    fireEvent.keyDown(c.numberInput, { key: 'Enter' });
    await waitFor(() => expect(c.numberInput.value).toBe('1'));
  });

  it('프레임이_0건이면_상단바는_남고_모든_이동_컨트롤이_비활성이다', async () => {
    mockApis(0);
    renderReviewPage();

    const c = await navBar();
    expect(c.first).toBeDisabled();
    expect(c.prev).toBeDisabled();
    expect(c.next).toBeDisabled();
    expect(c.last).toBeDisabled();
    expect(c.numberInput).toBeDisabled();
    expect(c.slider).toBeDisabled();
    expect(c.totalCount.textContent).toContain('0');
    // 프레임이 없다는 사실은 썸네일 스트립이 안내한다(상단바는 자리를 지킨다).
    expect(screen.getByTestId('frame-timeline-empty')).toBeInTheDocument();
  });

  // ── 접근성 ──────────────────────────────────────────────────────
  it('상단바와_슬라이더_번호입력에_접근_가능한_이름과_범위가_노출된다', async () => {
    mockApis(4);
    renderReviewPage();

    const { bar, slider } = await navBar();

    expect(bar.tagName).toBe('NAV');
    expect(bar).toHaveAttribute('aria-label', '프레임 이동 바');
    expect(within(bar).getByRole('group', { name: '프레임 이동' })).toBeInTheDocument();
    expect(within(bar).getByLabelText('프레임 번호')).toBeInTheDocument();

    // 슬라이더는 역할·범위를 노출한다(0-base 인덱스 축).
    const sliderByRole = within(bar).getByRole('slider', {
      name: '프레임 위치 슬라이더',
    });
    expect(sliderByRole).toBe(slider);
    expect(slider).toHaveAttribute('min', '0');
    expect(slider).toHaveAttribute('max', '3');
  });
});
