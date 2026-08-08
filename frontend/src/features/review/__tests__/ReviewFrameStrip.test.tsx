// SCREEN-019 §프레임 썸네일 스트립 — 위치·접기/펼치기 회귀 가드.
//
// 사양: `purpose` — "그 아래 프레임 이동 컨트롤이 있는 상단바, **캔버스 위의** 프레임 썸네일 스트립".
//       스트립 섹션은 접기/펼치기 토글을 함께 갖는다.
//
// 이 파일이 지키는 것
//  ① 스트립이 **캔버스보다 앞선 DOM 순서**에 있다(구 구현은 화면 최하단 Footer 였다).
//  ② 접기 토글이 `aria-expanded` 를 바꾸고 썸네일 목록이 **DOM 에서 사라진다**
//     — aria-hidden 으로 감추기만 하면 보이지 않는 버튼에 Tab 포커스가 갇힌다.
//  ③ 접었다 펴도 프레임 이동은 **같은 경로(handleGoToFrame)** 로 수렴한다.
//     상단 이동 바 → 스트립 카운터, 스트립 썸네일 → 상단 이동 바 양방향으로 확인한다.
//  ④ 기본값은 **펼침**(사양이 스트립을 화면 구성 요소로 명시한다).
//  ⑤ 접힘 상태에서도 진행률·카운터·토글 줄은 남는다(다시 펼칠 수단 유지).

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

describe('SCREEN-019 프레임 썸네일 스트립 (캔버스 위 + 접기/펼치기)', () => {
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

  // ── ① 위치 ───────────────────────────────────────────────────────
  it('썸네일_스트립은_캔버스보다_앞선_DOM_순서에_있다', async () => {
    mockApis(4);
    renderReviewPage();

    const strip = await screen.findByTestId('frame-timeline');
    const canvas = screen.getByTestId('review-canvas-readonly');

    // DOCUMENT_POSITION_FOLLOWING — canvas 가 strip 보다 문서 뒤에 온다.
    expect(
      strip.compareDocumentPosition(canvas) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    // 그리드 칸 기준으로도 확인한다 — 헤더 → 이동 바 → 스트립 → 캔버스 순.
    const page = screen.getByTestId('review-page');
    const cells = Array.from(page.children);
    const stripCell = screen.getByTestId('review-timeline-placeholder');
    expect(stripCell.parentElement).toBe(page);
    expect(cells.indexOf(stripCell)).toBeLessThan(cells.indexOf(canvas));
    // 이동 바 바로 다음 칸이 스트립이다(사이에 다른 칸이 끼지 않는다).
    expect(
      screen.getByTestId('review-frame-nav-bar').nextElementSibling,
    ).toBe(stripCell);
  });

  it('스트립_행은_자기_높이만_갖고_캔버스가_남은_높이를_차지한다', async () => {
    mockApis(4);
    renderReviewPage();

    await screen.findByTestId('frame-timeline');
    const page = screen.getByTestId('review-page');

    // 헤더 / 이동 바 / 스트립 / 캔버스 — 스트립은 auto, 캔버스 행만 1fr.
    // 스트립 행에 1fr 을 주면 썸네일 개수·스크롤 내용에 따라 캔버스 높이가 흔들린다.
    expect(page.style.gridTemplateRows).toBe('64px auto auto 1fr');
  });

  // ── ④ 기본 펼침 ──────────────────────────────────────────────────
  it('기본값은_펼침이라_진입_직후_썸네일_목록이_보인다', async () => {
    mockApis(4);
    renderReviewPage();

    const toggle = await screen.findByTestId('frame-timeline-toggle');
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(await screen.findByTestId('frame-timeline-strip')).toBeInTheDocument();
    expect(screen.getByTestId('frame-timeline-thumb-0')).toBeInTheDocument();
  });

  // ── ② 접기 토글 ──────────────────────────────────────────────────
  it('접기_토글이_aria_expanded_를_바꾸고_썸네일_목록이_DOM_에서_사라진다', async () => {
    mockApis(4);
    renderReviewPage();

    const toggle = await screen.findByTestId('frame-timeline-toggle');
    await screen.findByTestId('frame-timeline-strip');

    fireEvent.click(toggle);

    await waitFor(() =>
      expect(toggle).toHaveAttribute('aria-expanded', 'false'),
    );
    // 접힘은 aria-hidden 이 아니라 DOM 제거다 — 보이지 않는 버튼에 포커스가 갇히지 않는다.
    expect(screen.queryByTestId('frame-timeline-strip')).toBeNull();
    expect(screen.queryByTestId('frame-timeline-thumb-0')).toBeNull();

    // ⑤ 접혀도 진행률·카운터·토글은 남아 다시 펼칠 수 있다.
    expect(screen.getByTestId('frame-timeline-progress')).toBeInTheDocument();
    expect(screen.getByTestId('frame-timeline-counter')).toHaveTextContent('1 / 4');
    expect(toggle).toBeVisible();

    fireEvent.click(toggle);
    await waitFor(() =>
      expect(toggle).toHaveAttribute('aria-expanded', 'true'),
    );
    expect(screen.getByTestId('frame-timeline-strip')).toBeInTheDocument();
  });

  it('접기_펼치기_후에도_toolbar_접근성_계약이_유지된다', async () => {
    mockApis(4);
    renderReviewPage();

    const timeline = await screen.findByTestId('frame-timeline');
    const toggle = screen.getByTestId('frame-timeline-toggle');

    expect(timeline).toHaveAttribute('role', 'toolbar');
    expect(timeline).toHaveAttribute('aria-orientation', 'horizontal');

    fireEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'false'));
    expect(timeline).toHaveAttribute('role', 'toolbar');
    expect(timeline).toHaveAttribute('aria-orientation', 'horizontal');

    fireEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'true'));
    expect(timeline).toHaveAttribute('role', 'toolbar');
    // 썸네일은 접었다 편 뒤에도 선택 가능한 요소(현재 프레임 표시 포함)로 남는다.
    expect(screen.getByTestId('frame-timeline-thumb-0')).toHaveAttribute(
      'aria-current',
      'true',
    );
  });

  // ── ③ 단일 이동 경로 ─────────────────────────────────────────────
  it('접었다_펴도_프레임_이동은_같은_경로로_수렴한다', async () => {
    mockApis(4);
    renderReviewPage();

    // ⚠ 토글은 프레임 로드 후에야 나타난다(로드 전엔 빈 안내). 먼저 기다린 뒤 컨트롤을 잡는다 —
    //   기다리지 않으면 프레임 0건인 과도기 상태를 검증하게 된다.
    const toggle = await screen.findByTestId('frame-timeline-toggle');
    const bar = screen.getByTestId('review-frame-nav-bar');
    const numberInput = within(bar).getByTestId(
      'frame-number-input',
    ) as HTMLInputElement;
    const next = within(bar).getByRole('button', { name: '다음 프레임' });
    const counter = () => screen.getByTestId('frame-timeline-counter').textContent;

    // 접은 상태에서 상단 이동 바로 이동 → 스트립 카운터가 같은 상태를 본다.
    fireEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'false'));

    fireEvent.click(next);
    await waitFor(() => expect(numberInput.value).toBe('2'));
    expect(counter()).toContain('2 / 4');

    // 다시 펼친 뒤 썸네일 클릭 → 상단 이동 바가 같은 상태를 본다.
    fireEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'true'));

    fireEvent.click(await screen.findByTestId('frame-timeline-thumb-3'));
    await waitFor(() => expect(numberInput.value).toBe('4'));
    expect(counter()).toContain('4 / 4');
  });

  it('프레임이_0건이면_접기_토글_없이_빈_안내만_남는다', async () => {
    mockApis(0);
    renderReviewPage();

    expect(await screen.findByTestId('frame-timeline-empty')).toBeInTheDocument();
    // 접을 대상이 없으므로 토글도 두지 않는다(빈 컨트롤 노출 방지).
    expect(screen.queryByTestId('frame-timeline-toggle')).toBeNull();
  });
});
