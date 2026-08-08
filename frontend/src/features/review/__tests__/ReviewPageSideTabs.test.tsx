// SCREEN-019 검수 상세 — 우측 패널 탭 구조(객체 / 메타 / 이슈).
//
// 사양: 우측 패널은 3개 탭으로 전환된다. '객체' 탭은 카테고리 트리·선택 객체 속성·검수 메모를,
//   '메타' 탭은 이벤트 어노테이션과 외부 시계열 메타를, '이슈' 탭은 서버 연동 문의 스레드를 담는다.
//
// 구 구조는 5개 패널을 세로로 나열해 `role="tab"` 이 한 건도 없었다. 이 파일은
//   ① 3개 탭이 WAI-ARIA Tabs 계약(tablist/tab/tabpanel + aria-selected/aria-controls)을 갖추고
//   ② 방향키로 이동하며
//   ③ 탭을 옮겨도 캔버스와 양방향 동기화되는 객체 선택이 유지되고
//   ④ 구 5개 패널의 기능이 탭 어딘가에 전부 남아 있는지
//   를 고정한다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

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
import { ReviewPage } from '@/pages/ReviewPage';
import { useReviewSelectionStore } from '@/features/review/store/useReviewSelectionStore';
import { renderWithProviders } from '@/test/renderWithProviders';

const FRAME_SRC_SN = 501;
const LABEL_ID = 9001;

const baseReview = {
  id: 10,
  videoId: 1,
  cctvName: 'CCTV-1',
  workerId: 7,
  workerName: '홍길동',
  submittedAt: '2026-05-07T01:30:00Z',
  labelCount: 1,
  status: 'REVIEWING',
};

const frameList = {
  videoId: 1,
  totalFrames: 1,
  frames: [
    {
      srcSn: FRAME_SRC_SN,
      frameNo: 0,
      imageUrl: 'http://example.test/frames/501.jpg',
      labels: [
        {
          id: LABEL_ID,
          lblTypeCd: 'BBOX',
          label: '사람',
          labelId: 3,
          points: [
            [10, 20],
            [110, 220],
          ],
          confScore: 0.91,
          autoLblYn: 'Y',
          trackId: null,
        },
      ],
    },
  ],
};

function mockCommon(mock: MockAdapter) {
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
  mock.onGet('/reviews/1/frames').reply(200, {
    success: true,
    data: frameList,
    message: null,
    errorCode: null,
  });
  mock.onGet('/videos/1/issues').reply(200, {
    success: true,
    data: [],
    message: null,
    errorCode: null,
  });
  mock.onGet('/videos/1/event-annotation').reply(404, {
    success: false,
    data: null,
    message: '없음',
    errorCode: 'NOT_FOUND',
  });
  mock.onGet(`/frames/${FRAME_SRC_SN}/meta`).reply(200, {
    success: true,
    data: { items: [] },
    message: null,
    errorCode: null,
  });
}

async function renderReviewPage() {
  renderWithProviders(<ReviewPage />, {
    initialEntries: ['/review/10'],
    routes: [{ path: '/review/:id', element: <ReviewPage /> }],
  });
  // 탭 목록이 뜨면 검수 데이터 로드가 끝난 것이다.
  await screen.findByTestId('review-side-tablist');
}

describe('SCREEN-019 검수 우측 패널 — 탭 구조', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mockCommon(mock);
    useReviewSelectionStore.getState().clear();
  });

  afterEach(() => {
    mock.restore();
    useReviewSelectionStore.getState().clear();
  });

  it('우측_패널이_객체_메타_이슈_3개_탭으로_구성된다', async () => {
    await renderReviewPage();

    const tablist = screen.getByRole('tablist', { name: '검수 우측 패널 탭' });
    const tabs = within(tablist).getAllByRole('tab');
    expect(tabs.map((t) => t.textContent?.trim())).toEqual(['객체', '메타', '이슈']);
  });

  it('기본_선택_탭은_객체이며_객체_탭패널만_렌더된다', async () => {
    await renderReviewPage();

    expect(screen.getByTestId('review-tab-objects')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('review-tab-meta')).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByTestId('review-tab-issues')).toHaveAttribute('aria-selected', 'false');

    expect(screen.getByTestId('review-panel-objects')).toBeInTheDocument();
    expect(screen.queryByTestId('review-panel-meta')).toBeNull();
    expect(screen.queryByTestId('review-panel-issues')).toBeNull();
  });

  it('탭과_탭패널이_aria_controls_labelledby_로_상호_연결된다', async () => {
    await renderReviewPage();

    const objectsTab = screen.getByTestId('review-tab-objects');
    const objectsPanel = screen.getByTestId('review-panel-objects');
    expect(objectsPanel).toHaveAttribute('role', 'tabpanel');
    expect(objectsTab.getAttribute('aria-controls')).toBe(objectsPanel.getAttribute('id'));
    expect(objectsPanel.getAttribute('aria-labelledby')).toBe(objectsTab.getAttribute('id'));
  });

  it('선택된_탭만_tabIndex_0_이고_나머지는_마이너스1_이다', async () => {
    await renderReviewPage();

    expect(screen.getByTestId('review-tab-objects')).toHaveAttribute('tabindex', '0');
    expect(screen.getByTestId('review-tab-meta')).toHaveAttribute('tabindex', '-1');
    expect(screen.getByTestId('review-tab-issues')).toHaveAttribute('tabindex', '-1');
  });

  it('메타_탭_클릭하면_메타_패널이_뜨고_객체_패널은_사라진다', async () => {
    await renderReviewPage();

    await userEvent.click(screen.getByTestId('review-tab-meta'));

    expect(screen.getByTestId('review-tab-meta')).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByTestId('review-meta-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('review-panel-objects')).toBeNull();
  });

  it('이슈_탭_클릭하면_문의_스레드_패널이_뜬다', async () => {
    await renderReviewPage();

    await userEvent.click(screen.getByTestId('review-tab-issues'));

    expect(screen.getByTestId('review-tab-issues')).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByTestId('issue-thread-panel')).toBeInTheDocument();
  });

  it('방향키_오른쪽_왼쪽으로_탭을_이동하고_포커스가_따라간다', async () => {
    await renderReviewPage();

    const objectsTab = screen.getByTestId('review-tab-objects');
    objectsTab.focus();

    await userEvent.keyboard('{ArrowRight}');
    await waitFor(() => {
      expect(screen.getByTestId('review-tab-meta')).toHaveAttribute('aria-selected', 'true');
    });
    expect(screen.getByTestId('review-tab-meta')).toHaveFocus();

    await userEvent.keyboard('{ArrowLeft}');
    await waitFor(() => {
      expect(screen.getByTestId('review-tab-objects')).toHaveAttribute('aria-selected', 'true');
    });
    expect(screen.getByTestId('review-tab-objects')).toHaveFocus();
  });

  it('방향키_이동은_양끝에서_순환한다', async () => {
    await renderReviewPage();

    screen.getByTestId('review-tab-objects').focus();
    // 첫 탭에서 왼쪽 → 마지막 탭('이슈')
    await userEvent.keyboard('{ArrowLeft}');
    await waitFor(() => {
      expect(screen.getByTestId('review-tab-issues')).toHaveAttribute('aria-selected', 'true');
    });
    // 마지막 탭에서 오른쪽 → 첫 탭('객체')
    await userEvent.keyboard('{ArrowRight}');
    await waitFor(() => {
      expect(screen.getByTestId('review-tab-objects')).toHaveAttribute('aria-selected', 'true');
    });
  });

  it('Home_End_키로_첫_탭_마지막_탭으로_이동한다', async () => {
    await renderReviewPage();

    screen.getByTestId('review-tab-objects').focus();
    await userEvent.keyboard('{End}');
    await waitFor(() => {
      expect(screen.getByTestId('review-tab-issues')).toHaveAttribute('aria-selected', 'true');
    });
    await userEvent.keyboard('{Home}');
    await waitFor(() => {
      expect(screen.getByTestId('review-tab-objects')).toHaveAttribute('aria-selected', 'true');
    });
  });

  it('탭을_옮겼다_돌아와도_객체_선택이_유지된다', async () => {
    await renderReviewPage();

    // 객체 트리에서 라벨 선택 (캔버스와 양방향 동기화되는 축).
    const row = await screen.findByTestId(`object-list-row-${LABEL_ID}`);
    await userEvent.click(row);
    expect(useReviewSelectionStore.getState().selectedLabelId).toBe(LABEL_ID);
    expect(await screen.findByTestId('object-attributes-panel')).toBeInTheDocument();

    // 메타 탭으로 갔다가 객체 탭으로 복귀.
    await userEvent.click(screen.getByTestId('review-tab-meta'));
    await userEvent.click(screen.getByTestId('review-tab-objects'));

    // 선택이 살아 있고, 트리 행의 aria-selected 와 속성 패널이 그대로 복원된다.
    expect(useReviewSelectionStore.getState().selectedLabelId).toBe(LABEL_ID);
    const restoredRow = await screen.findByTestId(`object-list-row-${LABEL_ID}`);
    expect(restoredRow).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('object-attributes-panel')).toBeInTheDocument();
  });

  it('객체_트리_행은_listbox_option_접근성_계약을_유지한다', async () => {
    await renderReviewPage();

    const row = await screen.findByTestId(`object-list-row-${LABEL_ID}`);
    expect(row).toHaveAttribute('role', 'option');
    expect(row).toHaveAttribute('aria-selected', 'false');
    expect(row.closest('[role="listbox"]')).not.toBeNull();
  });

  it('구_5개_패널의_기능이_탭_어딘가에_전부_남아_있다', async () => {
    await renderReviewPage();

    // 객체 탭 — 객체 목록 + 속성 + 검수 메모
    const objectsPanel = screen.getByTestId('review-panel-objects');
    expect(within(objectsPanel).getByTestId('review-aside-object-list')).toBeInTheDocument();
    expect(within(objectsPanel).getByTestId('review-aside-attributes')).toBeInTheDocument();
    expect(within(objectsPanel).getByTestId('review-memo-panel')).toBeInTheDocument();

    // 메타 탭 — 메타 패널
    await userEvent.click(screen.getByTestId('review-tab-meta'));
    const metaPanel = await screen.findByTestId('review-panel-meta');
    expect(within(metaPanel).getByTestId('review-meta-panel')).toBeInTheDocument();

    // 이슈 탭 — 문의 스레드
    await userEvent.click(screen.getByTestId('review-tab-issues'));
    const issuesPanel = await screen.findByTestId('review-panel-issues');
    expect(within(issuesPanel).getByTestId('issue-thread-panel')).toBeInTheDocument();
  });

  it('승인_반려_액션은_헤더_단독이며_탭_안에는_없다', async () => {
    await renderReviewPage();

    const header = screen.getByTestId('review-header');
    expect(header).toContainElement(screen.getByTestId('review-action-approve'));
    expect(header).toContainElement(screen.getByTestId('review-action-reject'));

    const aside = screen.getByTestId('review-aside');
    expect(within(aside).queryByTestId('review-action-approve')).toBeNull();
    expect(within(aside).queryByTestId('review-action-reject')).toBeNull();
    expect(screen.queryByTestId('review-action-bar')).toBeNull();
  });

  it('미해소_문의가_있으면_이슈_탭에_건수_배지가_뜬다', async () => {
    mock.onGet('/videos/1/issues').reply(200, {
      success: true,
      data: [
        {
          issueSn: 1,
          rawSn: 1,
          srcSn: FRAME_SRC_SN,
          issueTypeCd: 'INQUIRY',
          issueSttsCd: 'OPEN',
          content: '이 객체 맞나요',
          regId: 'worker1',
          regDt: '2026-05-07T02:00:00Z',
          comments: [],
        },
      ],
      message: null,
      errorCode: null,
    });

    await renderReviewPage();

    const badge = await screen.findByTestId('review-tab-issues-badge');
    expect(badge).toHaveTextContent('1');
    expect(badge).toHaveAttribute('aria-label', '미해소 문의 1건');
  });
});
