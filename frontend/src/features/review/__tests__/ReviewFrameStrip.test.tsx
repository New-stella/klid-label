// SCREEN-019 §프레임 썸네일 스트립 — 위치·접기/펼치기 회귀 가드.
//
// 사양: `purpose` — "그 아래 프레임 이동 컨트롤이 있는 상단바, **캔버스 위의** 프레임 썸네일 스트립".
//       스트립 섹션은 접기/펼치기 토글을 함께 갖는다.
//
// 이 파일이 지키는 것
//  ① 스트립이 **캔버스보다 앞선 DOM 순서**에 있다(구 구현은 화면 최하단 Footer 였다).
//  ①-b 스트립은 **캔버스 컬럼 안**이라 우측 패널이 본문 상단부터 시작한다(확정 디자인
//     `.rv-main` = filmstrip + canvas-area). 구 구현은 스트립이 **전폭**이라 우측 패널이
//     스트립 아래에서 시작했다 — 그 배치로 되돌아가지 않게 고정한다.
//  ②  접기 토글이 `aria-expanded` 를 바꾸고 썸네일 목록이 **DOM 에서 사라진다**
//     — aria-hidden 으로 감추기만 하면 보이지 않는 버튼에 Tab 포커스가 갇힌다.
//  ③ 접었다 펴도 프레임 이동은 **같은 경로(handleGoToFrame)** 로 수렴한다.
//     상단 이동 바 → 스트립 카운터, 스트립 썸네일 → 상단 이동 바 양방향으로 확인한다.
//  ④ 기본값은 **펼침**(사양이 스트립을 화면 구성 요소로 명시한다).
//  ⑤ 접힘 상태에서도 카운터·토글은 남는다(다시 펼칠 수단 유지).
//  ⑥ 스트립은 **한 행**이다 — 디자인에 없는 머리말 줄(토글 텍스트 + 진행률 막대)을 다시
//     얹지 않는다. 진행 상태의 접근성 계약은 카운터가 이어받는다.

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';

// jsdom 환경에서 konva 가 native canvas 모듈을 요구하므로 mock 으로 대체.
vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

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

    // 캔버스 컬럼 안에서도 스트립이 캔버스보다 앞선다(사이에 다른 칸이 끼지 않는다).
    const column = screen.getByTestId('review-main-column');
    const stripCell = screen.getByTestId('review-timeline-placeholder');
    expect(stripCell.parentElement).toBe(column);
    expect(stripCell.nextElementSibling).toBe(canvas);
  });

  // ── ①-b 스트립은 캔버스 컬럼 안 (우측 패널이 상단부터 시작한다) ────────
  it('스트립은_캔버스_컬럼_안이라_우측_패널이_스트립_아래로_밀리지_않는다', async () => {
    mockApis(4);
    renderReviewPage();

    await screen.findByTestId('frame-timeline');
    const page = screen.getByTestId('review-page');
    const column = screen.getByTestId('review-main-column');
    const aside = screen.getByTestId('review-aside');
    const stripCell = screen.getByTestId('review-timeline-placeholder');

    // 캔버스 컬럼과 우측 패널은 **같은 행의 나란한 칸**이다 — 둘 다 page 의 직계 자식이고
    // 패널이 컬럼 바로 다음에 온다. 스트립은 그 컬럼 **안**이라 패널을 아래로 밀지 못한다.
    expect(column.parentElement).toBe(page);
    expect(aside.parentElement).toBe(page);
    expect(column.nextElementSibling).toBe(aside);
    expect(column.contains(stripCell)).toBe(true);
    expect(aside.contains(stripCell)).toBe(false);

    // 스트립이 다시 전폭(2열 span)으로 돌아가지 않게 고정한다.
    expect(stripCell.style.gridColumn).toBe('');
    expect(column.style.gridColumn).toBe('');
  });

  it('스트립_행은_자기_높이만_갖고_캔버스가_남은_높이를_차지한다', async () => {
    mockApis(4);
    renderReviewPage();

    await screen.findByTestId('frame-timeline');
    const page = screen.getByTestId('review-page');

    // 헤더(64px) / 이동 바(auto) / 본문(1fr) — 스트립은 본문 행 **안**에 있어 자체 행을 갖지 않는다.
    expect(page.style.gridTemplateRows).toBe('64px auto 1fr');
    // 컬럼 안에서 스트립은 shrink-0, 캔버스만 flex-1 — 스트립에 1fr 을 주면 썸네일 개수·스크롤
    // 내용에 따라 캔버스 높이가 흔들린다.
    expect(screen.getByTestId('review-timeline-placeholder').className).toContain(
      'shrink-0',
    );
    expect(screen.getByTestId('review-canvas-readonly').className).toContain('flex-1');
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

    // ⑤ 접혀도 카운터·토글은 남아 다시 펼칠 수 있다.
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

  // ── ⑥ 머리말 줄 없음 (한 행 구성) ───────────────────────────────
  it('스트립에_디자인에_없는_머리말_줄을_두지_않는다', async () => {
    mockApis(4);
    renderReviewPage();

    const toggle = await screen.findByTestId('frame-timeline-toggle');
    const strip = screen.getByTestId('frame-timeline-strip');
    const counter = screen.getByTestId('frame-timeline-counter');

    // 구 구현의 머리말 줄 구성물 — 진행률 막대와 토글의 '프레임 스트립' 텍스트는 두지 않는다.
    expect(screen.queryByTestId('frame-timeline-progress-bar')).toBeNull();
    expect(screen.queryByText('프레임 스트립')).toBeNull();
    // 토글은 아이콘 전용이라 접근성 이름은 aria-label 이 담당한다.
    expect(toggle).toHaveAccessibleName('썸네일 스트립 접기');
    expect(toggle.textContent).toBe('');

    // 토글·썸네일·카운터가 **한 행**에 나란히 있다(같은 부모의 형제).
    const row = toggle.parentElement;
    expect(row).not.toBeNull();
    expect(row).toContainElement(counter);
    expect(row).toContainElement(strip);
    expect(counter.parentElement).toBe(row);

    // 진행 상태의 접근성 계약은 사라지지 않고 카운터가 이어받는다.
    expect(counter).toHaveAttribute('role', 'progressbar');
    expect(counter).toHaveAttribute('aria-valuenow', '1');
    expect(counter).toHaveAttribute('aria-valuemax', '4');
  });

  it('프레임이_0건이면_접기_토글_없이_빈_안내만_남는다', async () => {
    mockApis(0);
    renderReviewPage();

    expect(await screen.findByTestId('frame-timeline-empty')).toBeInTheDocument();
    // 접을 대상이 없으므로 토글도 두지 않는다(빈 컨트롤 노출 방지).
    expect(screen.queryByTestId('frame-timeline-toggle')).toBeNull();
  });
});
