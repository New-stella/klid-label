// SCR-REVIEW-002 — 검수 화면 우측 aside 에 event_annotation·시계열 메타 읽기 표시(RED→GREEN).
//
// 근본원인: 검수자가 실제 진입하는 ReviewPage(/review/:id)는 메타 패널을 전혀 렌더하지
//   않아 검수자가 event_annotation/시계열 메타를 볼 수 없었다. 읽기 전용 표시를 추가한다.
//   (승인/반려 버튼은 없음 — event_annotation/시계열 확정은 영상 승인 시 자동 동결에 위임)

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// jsdom 환경에서 konva 가 native canvas 모듈을 요구하므로 mock 으로 대체.
vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { ReviewPage } from '@/pages/ReviewPage';

const baseReview = {
  id: 10,
  videoId: 1,
  cctvName: 'CCTV-1',
  workerId: 7,
  workerName: '홍길동',
  submittedAt: '2026-05-07T01:30:00Z',
  labelCount: 12,
  status: 'REVIEWING',
};

const FRAME_SRC_SN = 501;

const frameList = {
  videoId: 1,
  totalFrames: 1,
  frames: [
    {
      srcSn: FRAME_SRC_SN,
      frameNo: 0,
      imageUrl: 'http://example.test/frames/501.jpg',
      labels: [],
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
}

/**
 * 검수 화면을 띄우고 우측 패널의 '메타' 탭을 연다.
 *
 * 우측 패널은 사양대로 객체/메타/이슈 3개 탭이며 기본 탭이 '객체'라, 메타 패널을 보려면
 * 탭 전환이 선행돼야 한다. 이 파일의 검사 대상은 전부 메타 탭 내용이므로 헬퍼가 함께 연다.
 */
async function renderReviewPage() {
  renderWithProviders(<ReviewPage />, {
    initialEntries: ['/review/10'],
    routes: [{ path: '/review/:id', element: <ReviewPage /> }],
  });
  const metaTab = await screen.findByTestId('review-tab-meta');
  await userEvent.click(metaTab);
}

describe('ReviewPage 메타 읽기 표시', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mockCommon(mock);
  });

  afterEach(() => {
    mock.restore();
  });

  it('ReviewPage_메타_읽기표시_event_annotation_값_렌더', async () => {
    mock.onGet('/videos/1/event-annotation').reply(200, {
      success: true,
      data: {
        rawSn: 1,
        evntAnnoSn: 55,
        reviewStatus: 'APPROVED',
        regId: 'worker1',
        mdfcnId: null,
        payload: {
          event_class: '화재발생',
          question: '무슨 일이 일어나고 있나요?',
          answer: '건물에서 연기가 피어오릅니다.',
          caption: {
            c1: {
              caption_text: '연기가 올라온다',
              cot: ['연기 관찰', '화재 추정'],
            },
          },
          evidence: {
            c1: {
              evidence_text: '2번 객체에서 연기',
              frame_id: [501],
              obj_id: ['obj-2'],
              obj_label: ['smoke'],
              obj_bbox: [[10, 20, 30, 40]],
            },
          },
        },
      },
      message: null,
      errorCode: null,
    });
    mock.onGet(`/frames/${FRAME_SRC_SN}/meta`).reply(200, {
      success: true,
      data: { items: [] },
      message: null,
      errorCode: null,
    });

    await renderReviewPage();

    const panel = await screen.findByTestId('review-meta-panel');
    // event_class / question / answer 읽기 표시
    await waitFor(() => {
      expect(within(panel).getByText('화재발생')).toBeInTheDocument();
    });
    expect(
      within(panel).getByText('무슨 일이 일어나고 있나요?'),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText('건물에서 연기가 피어오릅니다.'),
    ).toBeInTheDocument();
    // caption + CoT
    expect(within(panel).getByText('연기가 올라온다')).toBeInTheDocument();
    expect(within(panel).getByText(/연기 관찰/)).toBeInTheDocument();
    expect(within(panel).getByText(/화재 추정/)).toBeInTheDocument();
    // evidence — text + obj_id/obj_label/obj_bbox/frame_id
    expect(within(panel).getByText('2번 객체에서 연기')).toBeInTheDocument();
    expect(within(panel).getByText(/obj-2/)).toBeInTheDocument();
    expect(within(panel).getByText(/smoke/)).toBeInTheDocument();
    expect(within(panel).getByText(/501/)).toBeInTheDocument();
  });

  it('ReviewPage_시계열메타_항목_읽기표시', async () => {
    mock.onGet('/videos/1/event-annotation').reply(404, {
      success: false,
      data: null,
      message: '없음',
      errorCode: 'NOT_FOUND',
    });
    mock.onGet(`/frames/${FRAME_SRC_SN}/meta`).reply(200, {
      success: true,
      data: {
        items: [
          {
            metaSn: 9001,
            metaKey: '0-5',
            metaVal: '사람이 배회하고 있음',
            dataMetaReviewSn: 700,
            reviewStatus: 'APPROVED',
          },
          {
            metaSn: 9002,
            metaKey: '5-10',
            metaVal: '차량 진입',
            dataMetaReviewSn: null,
            reviewStatus: null,
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    await renderReviewPage();

    const panel = await screen.findByTestId('review-meta-panel');
    await waitFor(() => {
      expect(
        within(panel).getByText('사람이 배회하고 있음'),
      ).toBeInTheDocument();
    });
    expect(within(panel).getByText('차량 진입')).toBeInTheDocument();
    // reviewStatus 배지(있는 항목만) — 중립 한글 라벨
    expect(within(panel).getByText('승인됨')).toBeInTheDocument();
  });

  it('ReviewPage_메타_승인반려_버튼_미노출', async () => {
    mock.onGet('/videos/1/event-annotation').reply(200, {
      success: true,
      data: {
        rawSn: 1,
        evntAnnoSn: 55,
        reviewStatus: 'PENDING',
        regId: 'worker1',
        mdfcnId: null,
        payload: { event_class: '침입', question: 'Q', answer: 'A' },
      },
      message: null,
      errorCode: null,
    });
    mock.onGet(`/frames/${FRAME_SRC_SN}/meta`).reply(200, {
      success: true,
      data: {
        items: [
          {
            metaSn: 9001,
            metaKey: '0-5',
            metaVal: '메타값',
            dataMetaReviewSn: 700,
            reviewStatus: 'PENDING',
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    await renderReviewPage();

    const panel = await screen.findByTestId('review-meta-panel');
    await waitFor(() => {
      expect(within(panel).getByText('침입')).toBeInTheDocument();
    });
    // 읽기 전용 — 승인/반려/저장 버튼이 메타 패널에 없어야 한다.
    //
    // ★2026-08-27 정정: 구 단언 `queryByRole('button')).toBeNull()` 은 폐기했다. 확정 사양
    //   (SCREEN-019)이 <b>각 섹션을 접어서 감출 수 있어야 한다</b>고 규정하므로 섹션 토글
    //   버튼은 반드시 존재한다. 「버튼이 0개」로 고정하면 사양이 요구하는 접기 기능을 가드가
    //   막는다. 대신 <b>모든 버튼이 섹션 토글인지</b>를 단언한다 — 저장·승인·반려 버튼이
    //   하나라도 섞이면 그 버튼에는 aria-expanded 가 없어 이 단언이 잡는다.
    const buttons = within(panel).queryAllByRole('button');
    expect(buttons.length).toBeGreaterThan(0);
    for (const btn of buttons) {
      expect(btn).toHaveAttribute('aria-expanded');
    }
    expect(within(panel).queryByRole('button', { name: '저장' })).toBeNull();
    expect(within(panel).queryByTestId('ea-approve')).toBeNull();
    expect(within(panel).queryByTestId('ea-reject')).toBeNull();
    expect(within(panel).queryByTestId('ea-save')).toBeNull();
    expect(within(panel).queryByTestId('ts-approve-700')).toBeNull();
    // 편집 인풋도 없어야 한다 — 입력·선택·체크박스 어느 것도 렌더되지 않는다.
    expect(within(panel).queryByRole('textbox')).toBeNull();
    expect(within(panel).queryByRole('combobox')).toBeNull();
    expect(within(panel).queryByRole('checkbox')).toBeNull();
    expect(within(panel).queryByRole('radio')).toBeNull();
  });

  it('메타_0건_크래시없이_빈상태_렌더', async () => {
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

    await renderReviewPage();

    // 크래시 없이 패널 자체는 렌더된다.
    const panel = await screen.findByTestId('review-meta-panel');
    expect(panel).toBeInTheDocument();
    // 빈 상태 플레이스홀더 노출.
    expect(
      within(panel).getByTestId('review-meta-empty'),
    ).toBeInTheDocument();
  });
});
