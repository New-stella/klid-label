// 추적 안내 문구 통로 — **이 통로에는 mock 안내만 온다**(예산 절단은 문구로 말하지 않는다).
//
// ★ 바뀐 전제 — 서버가 절단을 안내 문구로 알리던 동작은 폐기됐다. 절단은 응답의 구조화된 값
//   (`truncated`/`resume`)으로만 말하고, 자동 추적(`yolo-track`) 경로는 아예 어떤 문구도 싣지 않는다.
//   ⇒ 이 통로에 남는 문구는 **mock 안내**(추론 모델 미로드로 결과에서 빠진 프레임이 있다는 신호)뿐이다.
//
// ★ 그래서 화면이 «절단을 보고한 응답의 문구는 버린다» 는 필터를 들고 있으면 그 필터가 곧 손실이 된다:
//   절단이면서 mock 이기도 한 응답에서 **유일한 신호인 mock 안내가 버려진다**. mock 프레임은 결과에
//   아예 없으므로, 못 보면 사용자는 왜 그 구간에 라벨이 없는지 알 수 없다.
//
// ★ 남은 몫은 여전히 문구가 아니라 **정확한 수**(`unprocessed`)로만 말한다 — 그 규칙은 그대로다.

import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useLabelStore } from '@/stores/useLabelStore';

import { publishAiWaitBudgets, resetAiWaitBudgets } from '../aiBudget';
import { sam2TrackAllChunks } from '../api';
import { Sam2TrackTool } from '../canvas/tools/Sam2TrackTool';

const SAM2_PATH_RE = /\/frames\/(\d+)\/sam2-track/;

const MOCK_PARTIAL_NOTICE = '일부 결과의 신뢰도를 보장할 수 없습니다.';
const MOCK_UNAVAILABLE_NOTICE = 'AI 모델 미로드 — 결과 신뢰 불가';

const TRIANGLE: number[][] = [
  [0, 0],
  [10, 0],
  [10, 10],
];

const RESUME_POLYGON: number[][] = [
  [900, 900],
  [910, 900],
  [910, 910],
  [905, 920],
];

function ok(data: unknown, message: string | null = null) {
  return [200, { success: true, data, message, errorCode: null }] as [number, unknown];
}

function trackedFor(srcSns: number[]) {
  return srcSns.map((s) => ({
    srcSn: s,
    trackId: 't-1',
    label: 'person',
    points: [
      [s, 0],
      [s, 1],
      [s, 2],
    ],
    score: 0.8,
  }));
}

describe('AI 추적 — 안내 통로에는 mock 안내만 온다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
    publishAiWaitBudgets({ sam2Track: { baseSec: 0, perFrameSec: 0, ceilingSec: 100_000 } });
  });

  afterEach(() => {
    mock.restore();
    resetAiWaitBudgets();
  });

  it('절단은_문구가_아니라_남은_수로만_전달된다', async () => {
    // given: 1차는 한 프레임만 처리하고 잘렸고, 2차는 더 나아가지 못한다(진행 0).
    //        두 응답 모두 **문구를 싣지 않는다** — 절단은 truncated/resume 로만 말하는 것이 계약이다.
    let calls = 0;
    mock.onPost(SAM2_PATH_RE).reply((config) => {
      calls += 1;
      const body = JSON.parse(String(config.data ?? '{}'));
      const asked = body.nextSrcSns as number[];
      if (calls === 1) {
        return ok({
          tracked: trackedFor(asked.slice(0, 1)),
          truncated: true,
          resume: { srcSn: asked[0], prevPolygon: RESUME_POLYGON, nextSrcSns: asked.slice(1) },
        });
      }
      // 진행이 없다 — 요청한 그대로를 남은 구간으로 돌려준다.
      return ok({
        tracked: [],
        truncated: true,
        resume: { srcSn: asked[0], prevPolygon: RESUME_POLYGON, nextSrcSns: asked },
      });
    });

    // when
    const res = await sam2TrackAllChunks(10, {
      trackId: 't-1',
      prevPolygon: TRIANGLE,
      label: 'person',
      nextSrcSns: [11, 12, 13],
    });

    // then: 못 끝낸 몫은 정확한 수로만 나오고, 안내 문구는 없다
    expect(res.truncated).toBe(true);
    expect(res.unprocessed).toBe(2);
    expect(res.message).toBeNull();
  });

  it('절단을_보고한_응답에_실린_mock_안내가_버려지지_않는다', async () => {
    // given: 1차 응답이 **절단이면서 동시에 mock 안내**를 싣는다. mock 프레임은 결과에서 빠지므로
    //        이 안내가 사용자가 그 사실을 알 수 있는 유일한 신호다. 2차는 남은 구간을 끝낸다.
    let calls = 0;
    mock.onPost(SAM2_PATH_RE).reply((config) => {
      calls += 1;
      const body = JSON.parse(String(config.data ?? '{}'));
      const asked = body.nextSrcSns as number[];
      if (calls === 1) {
        return ok(
          {
            tracked: trackedFor(asked.slice(0, 1)),
            truncated: true,
            resume: { srcSn: asked[0], prevPolygon: RESUME_POLYGON, nextSrcSns: asked.slice(1) },
          },
          MOCK_PARTIAL_NOTICE,
        );
      }
      return ok({ tracked: trackedFor(asked), truncated: false, resume: null });
    });

    // when
    const res = await sam2TrackAllChunks(20, {
      trackId: 't-1',
      prevPolygon: TRIANGLE,
      label: 'person',
      nextSrcSns: [21, 22, 23],
    });

    // then: 절단 응답에 실렸다는 이유로 버리지 않는다
    expect(calls).toBe(2);
    expect(res.unprocessed).toBe(0);
    expect(res.message).toBe(MOCK_PARTIAL_NOTICE);
  });

  it('여러_응답의_서로_다른_안내가_모두_남는다', async () => {
    // 첫 것만 보존하면 뒤 조각의 안내가 통째로 사라진다.
    const next = Array.from({ length: 60 }, (_, i) => 200 + i);
    let calls = 0;
    mock.onPost(SAM2_PATH_RE).reply((config) => {
      calls += 1;
      const body = JSON.parse(String(config.data ?? '{}'));
      const asked = body.nextSrcSns as number[];
      return ok(
        { tracked: trackedFor(asked), truncated: false, resume: null },
        calls === 1 ? MOCK_UNAVAILABLE_NOTICE : MOCK_PARTIAL_NOTICE,
      );
    });

    const res = await sam2TrackAllChunks(100, {
      trackId: 't-1',
      prevPolygon: TRIANGLE,
      label: 'person',
      nextSrcSns: next,
    });

    expect(calls).toBe(2);
    expect(res.message).toContain(MOCK_UNAVAILABLE_NOTICE);
    expect(res.message).toContain(MOCK_PARTIAL_NOTICE);
  });

  it('같은_안내가_여러_번_와도_한_번만_남는다', async () => {
    // 조각마다 같은 문구가 오면 그대로 이어 붙일 때 같은 말이 반복된다.
    const next = Array.from({ length: 60 }, (_, i) => 300 + i);
    mock.onPost(SAM2_PATH_RE).reply((config) => {
      const body = JSON.parse(String(config.data ?? '{}'));
      const asked = body.nextSrcSns as number[];
      return ok(
        { tracked: trackedFor(asked), truncated: false, resume: null },
        MOCK_PARTIAL_NOTICE,
      );
    });

    const res = await sam2TrackAllChunks(200, {
      trackId: 't-1',
      prevPolygon: TRIANGLE,
      label: 'person',
      nextSrcSns: next,
    });

    expect(res.message).toBe(MOCK_PARTIAL_NOTICE);
  });

  it('절단_응답에_실린_mock_안내가_화면에_표시된다', async () => {
    // 사용자가 실제로 보는 것 — mock 프레임은 결과에 없으므로 이 안내를 못 보면 «왜 이 구간엔
    // 라벨이 없지» 를 알 방법이 없다.
    let calls = 0;
    mock.onPost(SAM2_PATH_RE).reply((config) => {
      calls += 1;
      const body = JSON.parse(String(config.data ?? '{}'));
      const asked = body.nextSrcSns as number[];
      if (calls === 1) {
        return ok(
          {
            tracked: trackedFor(asked.slice(0, 1)),
            truncated: true,
            resume: { srcSn: asked[0], prevPolygon: RESUME_POLYGON, nextSrcSns: asked.slice(1) },
          },
          MOCK_PARTIAL_NOTICE,
        );
      }
      return ok({ tracked: trackedFor(asked), truncated: false, resume: null });
    });

    renderWithProviders(
      <Sam2TrackTool
        srcSn={55}
        prevPolygon={TRIANGLE}
        label="person"
        trackId="t-1"
        nextSrcSns={[56, 57, 58]}
      />,
    );

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /자동추적/i }));
    });

    // 추적이 끝났다(완료 표시가 뜬다)
    await waitFor(() => {
      expect(screen.getByRole('progressbar', { name: 'AI 추적 완료' })).toBeInTheDocument();
    });
    expect(calls).toBe(2);
    // mock 안내는 화면에 남는다
    expect(await screen.findByText(MOCK_PARTIAL_NOTICE)).toBeInTheDocument();
    // 다 끝냈으므로 «남은 프레임» 안내는 뜨지 않는다(이 안내는 서버 문구가 아니라 화면이 센 수다)
    expect(screen.queryByText(/남은 프레임/)).not.toBeInTheDocument();
  });
});
