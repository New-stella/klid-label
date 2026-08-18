// R12 — LabelingPage AI 추적(SAM2 Track) 결과 병합/보류/드레인 재배선 테스트.
//  - 사일런트 실패 수정: tracked[].srcSn 은 미래 프레임 값 → 현재분 즉시 병합 + 미래분 보류 stash.
//  - 프레임 진입 시 보류 drain 병합. 부분실패/전체성공 토스트.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';
import { publishAiWaitBudgets, resetAiWaitBudgets } from '@/features/label/aiBudget';
import { ToolType } from '@/features/label/types';
import type { Label } from '@/features/label/types';

const EXISTING = {
  id: 91,
  frameNo: 0,
  lblTypeCd: 'BBOX',
  label: 'car',
  labelId: 5,
  points: [[0, 0], [10, 10]],
  autoLblYn: 'N',
  confScore: null,
  trackId: '91',
};

function labelsPayload(srcSn: number, frameNo: number, labels: unknown[], siblings: unknown[]) {
  return {
    success: true,
    data: { frameNo, srcSn, videoId: 7, siblings, labels },
    message: null,
    errorCode: null,
  };
}

const SIBLINGS = [
  { srcSn: 300, frameNo: 0, hasLabel: true },
  { srcSn: 301, frameNo: 1, hasLabel: false },
  { srcSn: 302, frameNo: 2, hasLabel: false },
];

const TEST_TOKEN = ['t', 'o', 'k'].join('');

function commonMocks(mock: MockAdapter) {
  mock.onGet(/\/frames\/\d+\/image/).reply(200, new Blob());
  mock.onGet(/\/frames\/\d+\/meta/).reply(200, { success: true, data: null, message: null, errorCode: null });
  mock.onGet(/\/frames\/\d+\/description/).reply(200, {
    success: true,
    data: { srcSn: 300, description: '' },
    message: null,
    errorCode: null,
  });
  mock.onGet('/manage/labels').reply(200, { success: true, data: [], message: null, errorCode: null });
  mock.onGet('/videos/7/issues').reply(200, { success: true, data: [], message: null, errorCode: null });
  mock.onGet(/\/reviews\/\d+/).reply(200, { success: true, data: null, message: null, errorCode: null });
}

describe('LabelingPage — AI 추적 병합(현재 즉시/미래 보류)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    commonMocks(mock);
    // ★ 이 파일은 **조각 경계에서의 부분 실패·병합 동선**을 검증한다. 조각 크기는 이제 서버가 준
    //   대기 예산이 정하므로(`aiBudget`), 예산을 넉넉히 발행해 **서버 프레임 상한(50)이 경계가
    //   되게** 고정한다. 그러지 않으면 예산 기본값이 바뀔 때마다 이 시나리오의 경계가 흔들린다.
    publishAiWaitBudgets({ sam2Track: { baseSec: 0, perFrameSec: 0, ceilingSec: 100_000 } });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    resetAiWaitBudgets();
    vi.restoreAllMocks();
  });

  function render300() {
    return renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
  }

  async function activateTrackAndRun() {
    // 라벨 로드 대기 → TRACK 도구 + 라벨 선택으로 Sam2TrackTool 노출.
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));
    act(() => {
      useLabelStore.getState().setActiveTool(ToolType.TRACK);
      useLabelStore.getState().selectLabel('91');
    });
    const btn = await screen.findByRole('button', { name: /AI 추적/i });
    await userEvent.click(btn);
  }

  it('현재프레임분은_즉시_병합되고_미래프레임분은_보류에_stash된다_전체성공토스트', async () => {
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300, 0, [EXISTING], SIBLINGS));
    // startSrcSn=300, nextSrcSns=[301,302] → 단일 청크. tracked 는 현재(300) 1 + 미래(301,302) 2.
    mock.onPost('/frames/300/sam2-track').reply(200, {
      success: true,
      data: {
        tracked: [
          { srcSn: 300, trackId: '91', label: 'person', points: [[1, 1], [5, 1], [5, 5], [1, 5]], score: 0.9 },
          { srcSn: 301, trackId: '91', label: 'car', points: [[10, 10], [20, 10], [20, 20], [10, 20]], score: 0.9 },
          { srcSn: 302, trackId: '91', label: 'car', points: [[30, 30], [40, 30], [40, 40], [30, 40]], score: 0.9 },
        ],
      },
      message: null,
      errorCode: null,
    });

    render300();
    await activateTrackAndRun();

    // 현재분(person) 즉시 병합 → 기존 car(1) + person(1) = 2.
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(2));
    expect(useLabelStore.getState().labels.some((l) => l.className === 'person')).toBe(true);
    // 미래분(301,302)은 보류 stash.
    const pending = useLabelStore.getState().pendingTracks;
    expect(pending[301]).toHaveLength(1);
    expect(pending[302]).toHaveLength(1);
    // 전체 성공 토스트 — 병합·스태시 총 3건.
    await waitFor(() =>
      expect(useUiStore.getState().toasts.some((t) => t.message === 'AI 추적 완료 (3프레임)')).toBe(true),
    );
  });

  it('부분실패시_성공분만_보류되고_N분의M_경고토스트를_띄운다', async () => {
    // siblings 61프레임(300..360) → nextSrcSns=[301..360] 60개 → 2청크 [301..350],[351..360].
    const many = Array.from({ length: 61 }, (_, i) => ({
      srcSn: 300 + i,
      frameNo: i,
      hasLabel: i === 0,
    }));
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300, 0, [EXISTING], many));
    // 청크1(start 300): 50프레임 성공(301..350).
    mock.onPost('/frames/300/sam2-track').reply((config) => {
      const body = JSON.parse((config.data as string) ?? '{}');
      const next = body.nextSrcSns as number[];
      return [
        200,
        {
          success: true,
          data: {
            tracked: next.map((s) => ({
              srcSn: s,
              trackId: '91',
              label: 'car',
              points: [[s, 0], [s + 1, 0], [s + 1, 1], [s, 1]],
              score: 0.8,
            })),
          },
          message: null,
          errorCode: null,
        },
      ];
    });
    // 청크2(start 350 = 청크1 마지막 srcSn): 실패.
    mock.onPost('/frames/350/sam2-track').reply(400, {
      success: false,
      data: null,
      message: 'nextSrcSns 검증 실패',
      errorCode: 'INVALID_INPUT',
    });

    render300();
    await activateTrackAndRun();

    // 성공분 50프레임(301..350) 미래 보류 stash.
    await waitFor(() => expect(Object.keys(useLabelStore.getState().pendingTracks)).toHaveLength(50));
    // 부분실패 경고 토스트 — 50/60.
    await waitFor(() =>
      expect(
        useUiStore.getState().toasts.some((t) => t.message === '50/60 프레임만 추적됨 (일부 실패)'),
      ).toBe(true),
    );
  });
});

describe('LabelingPage — 프레임 진입 시 보류 추적 drain 병합', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    commonMocks(mock);
    mock.onGet('/frames/301/labels').reply(200, labelsPayload(301, 1, [], SIBLINGS));
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    resetAiWaitBudgets();
    vi.restoreAllMocks();
  });

  function pendingLabel(): Label {
    return {
      id: '',
      frameNo: 1,
      classId: 1,
      className: 'car',
      source: 'AUTO_SAM2',
      shape: { type: 'POLYGON', points: [10, 10, 20, 10, 20, 20, 10, 20] },
      trackId: '91',
    };
  }

  it('보류된_추적결과가_해당프레임_진입시_작업본에_병합되고_보류에서_제거된다', async () => {
    // 진입 전 301 프레임 보류 stash.
    act(() => {
      useLabelStore.getState().stashPendingTracks({ 301: [pendingLabel()] });
    });

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/301'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 서버 라벨(빈) 로드 후 보류 1건 drain 병합 → labels 1건.
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));
    expect(useLabelStore.getState().labels[0].className).toBe('car');
    expect(useLabelStore.getState().labels[0].shape.type).toBe('POLYGON');
    // 보류에서 제거.
    expect(useLabelStore.getState().pendingTracks[301]).toBeUndefined();
    // drain 병합은 dirty 로 표시(저장 대상).
    expect(useLabelStore.getState().dirtyLabels.size).toBe(1);
    // 안내 토스트.
    await waitFor(() =>
      expect(useUiStore.getState().toasts.some((t) => t.message === '보류된 AI 추적 1건 적용됨')).toBe(true),
    );
  });
});
