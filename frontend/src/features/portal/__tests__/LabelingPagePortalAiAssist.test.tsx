// 포털 라벨링 화면(SCREEN-029) — AI 보조 세 기능의 노출·흐름·창구 경로 통합 가드.
//
// 이 파일이 고정하는 계약:
//  - AI 탐지 팝업의 실행 버튼은 [탐지 실행] 하나다([일반]/[트랙] 없음) · 실행은 포털 탐지 창구로 나간다.
//  - AI 자동 추적 패널이 객체 탭에 서고, 실행은 포털 자동 추적 창구로 나간다.
//  - 좌측 도구바 「AI 자동 추적」은 실행하지 않고 객체 탭으로 전환해 패널로 포커스를 옮긴다.
//  - AI 기본값은 포털 창구로 조회한다 · 포털 채널에서 내부 AI 창구 호출은 0건이다.
//  - 진행 중 취소는 포털 취소 창구로 나간다.
//  - 선택 객체가 있어도 속성 패널에 선택 객체 AI 추적은 없다.
//
// ★ 판정은 **주소**로 한다 — 요청 모의는 등록되지 않은 주소도 이력에 남기므로 «내부 창구로 샜다»가
//   건수만 보는 단언을 통과한다.
//
// @design SCREEN-029, API-255, API-254, API-256, API-258, AC-1108

import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

// AI 분할 훅에 넘어간 인자 — 화면 → 캔버스 → 훅으로 포털 채널이 끝까지 닿는지(읽는 자리) 본다.
//   캔버스에서 실제 클릭으로 분할을 일으키기는 이 환경(캔버스 모의)에서 어렵고, 훅→창구 주소는
//   경로 시험(portalAiRoutes)과 훅 시험이 따로 고정한다.
const segmentHook = vi.hoisted(() => ({ args: [] as unknown[][] }));
vi.mock('@/features/label/hooks/useSam2Segment', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/label/hooks/useSam2Segment')>();
  return {
    ...actual,
    useSam2Segment: (...args: Parameters<typeof actual.useSam2Segment>) => {
      segmentHook.args.push(args);
      return actual.useSam2Segment(...args);
    },
  };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

const PORTAL_JWT = ['t', 'o', 'k'].join('');

const ok = <T,>(data: T) => ({ success: true, data, message: null, errorCode: null });

function labelsPayload() {
  return ok({
    frameNo: 0,
    srcSn: 555,
    videoId: 5,
    siblings: [
      { srcSn: 555, frameNo: 0 },
      { srcSn: 556, frameNo: 1 },
    ],
    labels: [{ id: 1, lblTypeCd: 'BBOX', label: 'person', points: [[1, 2], [30, 40]] }],
  });
}

describe('포털 라벨링 화면 — AI 보조', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    segmentHook.args.length = 0;
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: PORTAL_JWT,
      claims: { sub: 'u-99', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    mock.onGet('/portal/frames/555/labels').reply(200, labelsPayload());
    mock.onGet(/\/portal\/frames\/\d+\/image/).reply(200, new Blob([new Uint8Array([1])]));
    mock.onGet('/portal/ai-defaults').reply(200, ok({ confThreshold: 30, simplifyTolerance: 5 }));
    mock.onGet('/manage/labels').reply(200, ok([]));
    mock.onGet('/manage/labels/detect-candidates').reply(
      200,
      ok([{ labelId: 1, name: '사람', color: '#EF4444', type: 'BBOX', dtctTypeCd: 'person' }]),
    );
    mock.onPost('/portal/frames/555/autolabel').reply(200, ok({ srcSn: 555, savedCount: 0, labels: [] }));
    mock
      .onPost('/portal/frames/555/yolo-track')
      .reply(200, ok({ frames: [], truncated: false, resume: null }));
    mock.onPost(/\/portal\/ai-requests\/.+\/cancel/).reply(200, ok({ cancelled: true }));
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  function renderPortalLabel() {
    return renderWithProviders(<LabelingPage />, {
      initialEntries: ['/portal/label/555'],
      routes: [{ path: '/portal/label/:id', element: <LabelingPage /> }],
    });
  }

  async function waitForToolbar() {
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));
    return screen.getByTestId('label-toolbar-ai-group');
  }

  /** 포털 채널에서 나가서는 안 되는 내부 AI 창구 호출(주소 기준). */
  function internalAiCalls() {
    return mock.history.get
      .concat(mock.history.post)
      .map((r) => r.url ?? '')
      .filter((url) => /^\/(frames\/\d+\/(autolabel|sam2-segment|sam2-track|yolo-track)|ai-defaults|ai-requests\/)/.test(url));
  }

  it('AI_탐지_팝업은_탐지_실행_하나이고_실행은_포털_탐지_창구로_나간다', async () => {
    const user = userEvent.setup();
    renderPortalLabel();
    const aiGroup = await waitForToolbar();

    await user.click(within(aiGroup).getByRole('button', { name: 'AI 탐지' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByRole('button', { name: '일반' })).toBeNull();
    expect(within(dialog).queryByRole('button', { name: '트랙' })).toBeNull();
    expect(within(dialog).queryByText(/실행 방식을 고르세요/)).toBeNull();
    expect(within(dialog).queryByText(/후속 프레임이 없어 추적할 수 없습니다/)).toBeNull();

    const run = await within(dialog).findByRole('button', { name: '탐지 실행' });
    await waitFor(() => expect(run).toBeEnabled());
    await user.click(run);

    await waitFor(() =>
      expect(mock.history.post.map((r) => r.url)).toContain('/portal/frames/555/autolabel'),
    );
    expect(internalAiCalls()).toEqual([]);
  });

  it('AI_기본값은_포털_창구로_조회하고_내부_창구는_부르지_않는다', async () => {
    renderPortalLabel();
    await waitForToolbar();
    await waitFor(() =>
      expect(mock.history.get.map((r) => r.url)).toContain('/portal/ai-defaults'),
    );
    expect(internalAiCalls()).toEqual([]);
  });

  it('도구바_AI_자동_추적은_객체_탭으로_전환해_패널로_포커스를_옮기고_실행하지_않는다', async () => {
    const user = userEvent.setup();
    renderPortalLabel();
    const aiGroup = await waitForToolbar();

    // 메타 탭에 있다가 누른다 — 탭 전환이 함께 일어나야 한다.
    await user.click(screen.getByTestId('right-tab-meta'));
    expect(screen.getByTestId('right-tab-objects')).toHaveAttribute('aria-selected', 'false');

    await user.click(within(aiGroup).getByRole('button', { name: 'AI 자동 추적 패널로 이동' }));

    expect(screen.getByTestId('right-tab-objects')).toHaveAttribute('aria-selected', 'true');
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByTestId('auto-track-panel-heading')),
    );
    // 이동만 한다 — 자동 추적 요청은 나가지 않는다.
    expect(mock.history.post.filter((r) => (r.url ?? '').includes('yolo-track'))).toEqual([]);
  });

  it('자동_추적_패널_실행은_포털_자동_추적_창구로_나간다', async () => {
    const user = userEvent.setup();
    renderPortalLabel();
    await waitForToolbar();

    const panel = screen.getByTestId('auto-track-panel');
    await user.click(within(panel).getByRole('button', { name: 'AI 자동 추적' }));

    await waitFor(() =>
      expect(mock.history.post.map((r) => r.url)).toContain('/portal/frames/555/yolo-track'),
    );
    expect(internalAiCalls()).toEqual([]);
  });

  it('진행_중_취소는_포털_취소_창구로_나간다', async () => {
    // 응답을 붙잡아 둔다 — 진행 중 상태에서 취소한다.
    let release: () => void = () => {};
    mock.onPost('/portal/frames/555/autolabel').reply(
      () =>
        new Promise((resolve) => {
          release = () => resolve([200, ok({ srcSn: 555, savedCount: 0, labels: [] })]);
        }),
    );
    const user = userEvent.setup();
    renderPortalLabel();
    const aiGroup = await waitForToolbar();

    await user.click(within(aiGroup).getByRole('button', { name: 'AI 탐지' }));
    const dialog = await screen.findByRole('dialog');
    const run = await within(dialog).findByRole('button', { name: '탐지 실행' });
    await waitFor(() => expect(run).toBeEnabled());
    await user.click(run);
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('AI_DETECT'));

    act(() => {
      useLabelStore.getState().cancelBusy();
    });

    await waitFor(() =>
      expect(
        mock.history.post.some((r) => /^\/portal\/ai-requests\/[^/]+\/cancel$/.test(r.url ?? '')),
      ).toBe(true),
    );
    expect(internalAiCalls()).toEqual([]);
    release();
  });

  it('AI_분할_요청_훅에_포털_채널이_끝까지_닿는다', async () => {
    renderPortalLabel();
    await waitForToolbar();
    await screen.findByTestId('canvas-shell');
    expect(segmentHook.args.length).toBeGreaterThan(0);
    expect(segmentHook.args[segmentHook.args.length - 1]).toEqual([555, true]);
  });

  it('선택_객체가_있어도_속성_패널에_선택_객체_AI_추적은_없다', async () => {
    renderPortalLabel();
    await waitForToolbar();
    const id = useLabelStore.getState().labels[0]?.id;
    expect(id).toBeDefined();
    act(() => {
      useLabelStore.getState().selectLabel(id ?? null);
    });
    await waitFor(() => expect(useLabelStore.getState().selectedLabelId).toBe(id));
    // 대조 — 속성 패널이 실제로 선택 객체를 그리고 있다(공허한 부재 단언 방지: 선택이 없으면
    //   AI 추적 자리 자체가 서지 않아 부재 단언이 무조건 통과한다).
    await waitFor(() => expect(screen.getByTestId('object-attribute-id')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /^AI 추적/ })).toBeNull();
  });
});
