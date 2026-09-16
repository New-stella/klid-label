// 영상 상세 — 「처리 중」 진행 추적. [@design SCREEN-009] [@design API-167]
//
// ★ 무엇을 지키는 테스트인가
//   재기동은 접수만 확정되고 실행은 비동기로 넘어간다. 접수 직후의 상세는 **진행 로그가 직전 실패
//   그대로**라, 기본 폴링 규칙(FAIL = 배치 종료 → 폴링 중지)이 즉시 멈춘다. 그러면 사용자에게는
//   눌렀는데 아무 일도 일어나지 않는 것과 구분되지 않는다.
//   판정 축은 **영상 상태**다 — 서버가 접수 시점에 이미 처리 중(PROCESSING)으로 커밋하므로,
//   화면은 그 상태를 보고 ① 진행을 따라가고 ② 상한 창이 지나면 스스로 멈춘다.
//
// ⚠ 대조군을 함께 둔다 — "실패 상태에서는 폴링이 돌지 않는다"를 같이 고정하지 않으면, 폴링이 원래
//   항상 돌고 있었던 것인지 이 배선 덕분에 도는 것인지 이 테스트만으로는 구분되지 않는다.
// ⚠ 구 동작(재기동 버튼을 누른 사람에게만 60초 창을 열어 주던 배선)은 폐기됐다. 그래서 이 파일은
//   **버튼을 누르지 않고 들어온 경우**(일괄 재시작 후 상세 이동)도 함께 고정한다.

import MockAdapter from 'axios-mock-adapter';
import { act, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { apiClient } from '@/lib/api/client';
import {
  BATCH_PROCESSING_POLL_WINDOW_MS,
  LEAD_DEIDENT_ACCEPT_POLL_WINDOW_MS,
} from '@/features/video/hooks/useVideoDetail';
import { VideoDetailPage } from '@/pages/VideoDetailPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn() };
});

const DISPLAY_STAGES = [
  'DEIDENTIFY',
  'MARKING',
  'VLM',
  'FRAME_EXTRACT',
  'YOLO',
  'SAM2',
  'INTERPOLATE',
];

/**
 * VLM 에서 실패로 멈춘 영상.
 *
 * `status` 만 갈아 끼워 두 국면을 만든다 — 진행 로그·실패 사유는 **양쪽에서 동일**하다.
 * 그것이 실제 서버 응답 모양이고(로그는 다음 실행이 돌아야 갱신된다), 로그를 바꿔 버리면
 * 이 결함(접수됐는데 실패로 보인다)을 애초에 재현하지 못한다.
 */
function detailWithStatus(status: string) {
  return {
    rawSn: 42,
    vmsCctvId: 'CCTV-42',
    evntTypeCd: 'FIGHT',
    status,
    dataSttsCd: status,
    regDt: '2026-06-01T10:00:00',
    framePreviews: [],
    stages: DISPLAY_STAGES.map((name) => ({
      name,
      status:
        name === 'VLM' ? 'FAIL' : name === 'DEIDENTIFY' || name === 'MARKING' ? 'DONE' : 'PENDING',
      progress: null,
    })),
    batchFailureReason: '외부 시계열 분석 서버가 응답하지 않았습니다.',
    skippedStages: [],
  };
}

function replyWith(mock: MockAdapter, status: string) {
  mock.onGet('/videos/42').reply(200, {
    success: true,
    data: detailWithStatus(status),
    message: null,
    errorCode: null,
  });
}

function renderPage() {
  return renderWithProviders(<VideoDetailPage />, {
    initialEntries: ['/videos/42'],
    routes: [{ path: '/videos/:id', element: <VideoDetailPage /> }],
  });
}

describe('VideoDetailPage 「처리 중」 진행 추적', () => {
  let mock: MockAdapter;

  const detailGets = () => mock.history.get.filter((h) => h.url === '/videos/42').length;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'placeholder-jwt',
      claims: { sub: 'u-1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    vi.useRealTimers();
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('대조군_실패로_끝난_영상은_폴링하지_않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    replyWith(mock, 'FAILED');
    renderPage();

    await waitFor(() => expect(screen.getByTestId('batch-failure-panel')).toBeInTheDocument());
    const before = detailGets();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(20_000);
    });

    expect(detailGets()).toBe(before);
  });

  // ★★ 이 케이스가 이번 수정의 핵심이다 — 버튼을 누르지 않고 들어와도 따라간다.
  //    구 배선(접수 콜백으로만 창을 열던 것)에서는 이 경우 창이 아예 열리지 않았다.
  it('★처리_중이면_진행_로그가_실패_그대로여도_따라간다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    replyWith(mock, 'PROCESSING');
    renderPage();

    await waitFor(() => expect(screen.getByTestId('batch-failure-panel')).toBeInTheDocument());
    const before = detailGets();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(12_000);
    });

    expect(detailGets()).toBeGreaterThan(before);
  });

  it('★처리_중이면_실패가_아니라_처리_중이라고_말하고_재실행을_막는다', async () => {
    replyWith(mock, 'PROCESSING');
    renderPage();

    await waitFor(() => expect(screen.getByTestId('batch-failure-panel')).toBeInTheDocument());
    expect(screen.getByRole('heading', { name: /배치 처리 중/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /배치 재실행/ })).toBeDisabled();
  });

  it('상한_창이_지나면_스스로_멈춘다_고착_영상에서_무한_폴링을_만들지_않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    // PROCESSING 이 고착된 영상(별도 결함으로 인지됨) — 상태가 영영 바뀌지 않는다.
    replyWith(mock, 'PROCESSING');
    renderPage();

    await waitFor(() => expect(screen.getByTestId('batch-failure-panel')).toBeInTheDocument());

    await act(async () => {
      await vi.advanceTimersByTimeAsync(BATCH_PROCESSING_POLL_WINDOW_MS + 20_000);
    });
    const settled = detailGets();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(detailGets()).toBe(settled);
  });
});

// [@design API-167] [@design SCREEN-009] [@design AC-1133]
// ★ 선두 비식별 재시도 — 접수 응답의 단계가 대기(PENDING)이고 서버는 접수 뒤 비동기로 위탁한다.
//   배치 상태도 진행 로그도 움직이지 않으므로, 접수 직후 틈과 새 회차 진행을 따로 따라가야 한다.
describe('VideoDetailPage 선두 비식별 재시도 추적', () => {
  let mock: MockAdapter;
  /** 서버 상세 응답 — 시험이 국면마다 갈아 끼운다. */
  let detail: Record<string, unknown>;

  const detailGets = () => mock.history.get.filter((h) => h.url === '/videos/42').length;

  function leadDetail(history: { procLogSn: number; procSttsCd: string }[], extra = {}) {
    return {
      rawSn: 42,
      vmsCctvId: 'CCTV-42',
      evntTypeCd: 'FIGHT',
      status: 'PENDING',
      dataSttsCd: 'PENDING',
      deIdntfYn: 'F',
      regDt: '2026-06-01T10:00:00',
      framePreviews: [],
      stages: [],
      batchFailureReason: null,
      skippedStages: [],
      deidentHistory: history.map((h) => ({ ...h, reqKndCd: null })),
      ...extra,
    };
  }

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    detail = leadDetail([{ procLogSn: 1, procSttsCd: 'FAILED' }]);
    mock.onGet('/videos/42').reply(() => [
      200,
      { success: true, data: detail, message: null, errorCode: null },
    ]);
    mock.onPost('/videos/42/batch/retry').reply(200, {
      success: true,
      data: { rawSn: 42, stage: 'PENDING' },
      message: null,
      errorCode: null,
    });
    useAuthStore.setState({
      token: 'placeholder-jwt',
      claims: { sub: 'u-1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    vi.useRealTimers();
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function openAndAccept() {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    const button = await screen.findByRole('button', { name: /배치 재실행/ });
    await act(async () => {
      button.click();
    });
    await waitFor(() =>
      expect(mock.history.post.map((h) => h.url)).toContain('/videos/42/batch/retry'),
    );
    // 접수 응답을 본 화면이 추적 창을 무장하는 렌더까지 흘려보낸다 — 이 틈 없이 시간을 크게 밀면
    //   무장 전에 창이 이미 지난 것으로 계산된다(실사용에서는 렌더가 즉시 일어난다).
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
  }

  it('대조군_누르지_않으면_멈춘_실패를_폴링하지_않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    await screen.findByRole('button', { name: /배치 재실행/ });
    const before = detailGets();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(20_000);
    });
    expect(detailGets()).toBe(before);
  });

  it('작업자에게는_선두_비식별_실패_영상에서도_조치_영역과_재실행_버튼을_보이지_않는다', async () => {
    useAuthStore.setState({
      token: 'placeholder-jwt',
      claims: { sub: 'u-2', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderPage();

    // 화면이 다 그려진 뒤에 부재를 본다 — 로딩 중의 부재는 아무것도 증명하지 않는다.
    expect(await screen.findByRole('heading', { name: '비식별 이력' })).toBeInTheDocument();
    expect(screen.queryByTestId('batch-failure-panel')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /배치 재실행/ })).not.toBeInTheDocument();
  });

  it('★접수_직후_새_회차가_쌓이기_전에도_폴링을_시작한다', async () => {
    await openAndAccept();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    const before = detailGets();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(12_000);
    });
    expect(detailGets()).toBeGreaterThan(before);
  });

  it('새_회차가_진행_중이면_계속_따라가고_비식별이_성공하면_멈춘다', async () => {
    await openAndAccept();
    detail = leadDetail([
      { procLogSn: 2, procSttsCd: 'REQUESTED' },
      { procLogSn: 1, procSttsCd: 'FAILED' },
    ]);
    // 접수 창(60초)을 넘겨도 진행 중이면 처리 중 추적이 이어받는다.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(LEAD_DEIDENT_ACCEPT_POLL_WINDOW_MS + 10_000);
    });
    expect(await screen.findByRole('heading', { name: '배치 처리 중' })).toBeInTheDocument();
    const whileRunning = detailGets();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(12_000);
    });
    expect(detailGets()).toBeGreaterThan(whileRunning);

    // 성공 — 비식별 'Y' + 마킹 대기. 패널이 사라지고 폴링이 멈춘다.
    detail = leadDetail(
      [
        { procLogSn: 2, procSttsCd: 'SUCCEEDED' },
        { procLogSn: 1, procSttsCd: 'FAILED' },
      ],
      { status: 'MARKING_READY', dataSttsCd: 'MARKING_READY', deIdntfYn: 'Y' },
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6_000);
    });
    await waitFor(() => expect(screen.queryByTestId('batch-failure-panel')).not.toBeInTheDocument());
    const settled = detailGets();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(detailGets()).toBe(settled);
  });

  it('새_회차가_실패로_끝나면_멈춘다', async () => {
    await openAndAccept();
    detail = leadDetail([
      { procLogSn: 2, procSttsCd: 'FAILED' },
      { procLogSn: 1, procSttsCd: 'FAILED' },
    ]);
    // 새 회차를 받아 오는 한 번의 조회와, 이미 걸려 있던 타이머의 마지막 한 번까지 흘려보낸다.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(12_000);
    });
    const settled = detailGets();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(detailGets()).toBe(settled);
  });

  it('새_회차가_끝내_안_쌓여도_접수_창이_지나면_멈춘다_무한_폴링_없음', async () => {
    await openAndAccept();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(LEAD_DEIDENT_ACCEPT_POLL_WINDOW_MS + 10_000);
    });
    const settled = detailGets();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(detailGets()).toBe(settled);
  });
});
