// 추적 두 경로의 **부분 결과 계약** — 서버가 요청 단위 시간 예산을 다 쓰면 그때까지의 결과를
// 돌려주면서 「어디까지 했는지」를 알린다(200, 취소가 아니다).
//
// ★ 이 파일이 고정하는 것(사용자가 겪는 것 기준):
//   1. **남은 프레임의 라벨이 조용히 사라지지 않는다** — 잘린 응답이 오면 이어 보내 끝까지 간다.
//      화면이 `truncated`/`resume` 를 안 읽으면 정확히 그 손실이 난다. 이 파일의 존재 이유다.
//   2. 이어붙일 시드(전파 폴리곤)를 **화면이 만들지 않는다** — 결과 목록에서 역산할 수 없는 값이라
//      서버가 준 것을 그대로 다시 싣는다.
//   3. **진행이 0 인 응답에 같은 요청을 다시 보내지 않는다**(무한 재요청 차단).
//   4. **취소하면 다음 조각을 보내지 않는다.**
//   5. 이어 보낸 조각의 객체 식별자는 **앞 조각과 합쳐지지 않는다**(자동 추적은 요청마다 트래커가
//      리셋되므로, 같은 번호라도 같은 객체라는 근거가 없다).
//
// ⚠ 안내 문구는 판단 근거가 아니다 — 분기는 `truncated`/`resume` 로만 한다(문구 파싱 금지).

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { publishAiWaitBudgets, resetAiWaitBudgets, aiWaitChunkSize } from '../aiBudget';
import { sam2TrackAllChunks, SAM2_TRACK_MAX_FRAMES_PER_REQUEST } from '../api';
import { requestAutoTrackAll } from '../api/autoTrack';
import { buildAutoTrackReview } from '../utils/autoTrackResult';

const SAM2_PATH_RE = /\/frames\/(\d+)\/sam2-track/;
const YOLO_PATH_RE = /\/frames\/(\d+)\/yolo-track/;

const TRIANGLE: number[][] = [
  [0, 0],
  [10, 0],
  [10, 10],
];

/** 서버가 준 이어붙일 폴리곤 — 결과 목록의 어떤 좌표와도 겹치지 않게 잡는다(역산 여부 판별용). */
const RESUME_POLYGON: number[][] = [
  [900, 900],
  [910, 900],
  [910, 910],
  [905, 920],
];

function ok(data: unknown, message: string | null = null) {
  return [200, { success: true, data, message, errorCode: null }] as [number, unknown];
}

/** srcSn 기반 결정적 추적 결과. */
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

describe('SAM2 추적 — 잘린 응답 이어 보내기', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // 조각 분할이 아니라 «서버가 잘라 보낸 것을 이어 붙이는가» 를 본다 — 예산을 넉넉히 발행해
    // 화면쪽 분할이 끼어들지 않게 고정한다(분할 자체는 aiInferenceTimeout 이 본다).
    publishAiWaitBudgets({ sam2Track: { baseSec: 0, perFrameSec: 0, ceilingSec: 100_000 } });
  });

  afterEach(() => {
    mock.restore();
    resetAiWaitBudgets();
  });

  it('잘린_응답이_오면_남은_프레임을_이어_보내_결과가_유실되지_않는다', async () => {
    // given: 서버가 5프레임 중 앞 2건만 처리하고 나머지를 resume 으로 알린다
    const all = [11, 12, 13, 14, 15];
    const seen: { path: number; body: Record<string, unknown> }[] = [];
    mock.onPost(SAM2_PATH_RE).reply((config) => {
      const path = Number(SAM2_PATH_RE.exec(config.url ?? '')![1]);
      const body = JSON.parse(String(config.data ?? '{}'));
      seen.push({ path, body });
      const asked = body.nextSrcSns as number[];
      if (seen.length === 1) {
        return ok({
          tracked: trackedFor(asked.slice(0, 2)),
          truncated: true,
          resume: { srcSn: 12, prevPolygon: RESUME_POLYGON, nextSrcSns: asked.slice(2) },
        });
      }
      return ok({ tracked: trackedFor(asked), truncated: false, resume: null });
    });

    // when
    const res = await sam2TrackAllChunks(10, {
      trackId: 't-1',
      prevPolygon: TRIANGLE,
      label: 'person',
      nextSrcSns: all,
    });

    // then: 두 번 보냈고, 두 번째는 서버가 준 이어보내기 값 그대로다
    expect(seen).toHaveLength(2);
    expect(seen[1].path).toBe(12);
    expect(seen[1].body.srcSn).toBe(12);
    expect(seen[1].body.nextSrcSns).toEqual([13, 14, 15]);
    // 다섯 프레임이 모두 살아 있다 — 이것이 이 계약의 존재 이유다
    expect(res.tracked.map((t) => t.srcSn)).toEqual(all);
    expect(res.truncated).toBe(false);
  });

  it('이어붙일_폴리곤은_서버가_준_값이며_결과에서_역산하지_않는다', async () => {
    // ★ 처리했지만 결과 목록에서 빠지는 프레임이 있어 역산이 성립하지 않는다. 그래서 서버가
    //   준 전파 폴리곤을 그대로 다시 싣는다 — 여기서는 결과를 아예 비워 역산이 불가능하게 만든다.
    const seen: Record<string, unknown>[] = [];
    mock.onPost(SAM2_PATH_RE).reply((config) => {
      const body = JSON.parse(String(config.data ?? '{}'));
      seen.push(body);
      const asked = body.nextSrcSns as number[];
      if (seen.length === 1) {
        return ok({
          tracked: [], // 처리는 했으나 전부 제외됨(mock 등) — 역산할 좌표가 없다
          truncated: true,
          resume: { srcSn: 21, prevPolygon: RESUME_POLYGON, nextSrcSns: asked.slice(1) },
        });
      }
      return ok({ tracked: trackedFor(asked), truncated: false, resume: null });
    });

    await sam2TrackAllChunks(20, {
      trackId: 't-1',
      prevPolygon: TRIANGLE,
      label: 'person',
      nextSrcSns: [21, 22, 23],
    });

    expect(seen).toHaveLength(2);
    expect(seen[1].prevPolygon).toEqual(RESUME_POLYGON);
  });

  it('진행이_0_인_응답에는_같은_요청을_다시_보내지_않는다', async () => {
    // given: 예산이 한 프레임도 담지 못해 요청과 똑같은 resume 이 돌아온다
    let calls = 0;
    mock.onPost(SAM2_PATH_RE).reply((config) => {
      calls += 1;
      const body = JSON.parse(String(config.data ?? '{}'));
      return ok({
        tracked: [],
        truncated: true,
        resume: {
          srcSn: body.srcSn as number,
          prevPolygon: TRIANGLE,
          nextSrcSns: body.nextSrcSns as number[],
        },
      });
    });

    const res = await sam2TrackAllChunks(30, {
      trackId: 't-1',
      prevPolygon: TRIANGLE,
      label: 'person',
      nextSrcSns: [31, 32, 33],
    });

    // then: 한 번만 보낸다(그대로 되보내면 무한 재요청이다)
    expect(calls).toBe(1);
    // 그리고 못 끝냈다는 사실을 감추지 않는다
    expect(res.truncated).toBe(true);
    expect(res.resume?.nextSrcSns).toEqual([31, 32, 33]);
  });

  it('취소하면_다음_조각을_보내지_않는다', async () => {
    const controller = new AbortController();
    let calls = 0;
    mock.onPost(SAM2_PATH_RE).reply((config) => {
      calls += 1;
      const body = JSON.parse(String(config.data ?? '{}'));
      const asked = body.nextSrcSns as number[];
      // 첫 응답을 받은 직후 사용자가 취소한다
      controller.abort();
      return ok({
        tracked: trackedFor(asked.slice(0, 1)),
        truncated: true,
        resume: { srcSn: asked[0], prevPolygon: RESUME_POLYGON, nextSrcSns: asked.slice(1) },
      });
    });

    await sam2TrackAllChunks(
      40,
      { trackId: 't-1', prevPolygon: TRIANGLE, label: 'person', nextSrcSns: [41, 42, 43] },
      undefined,
      controller.signal,
    ).catch(() => undefined);

    expect(calls).toBe(1);
  });

  it('이어_보낼_때마다_진행이_올라온다', async () => {
    // 멈춘 것처럼 보이면 안 된다 — 이어 보내는 동안에도 진행이 갱신된다.
    const seen: number[] = [];
    let calls = 0;
    mock.onPost(SAM2_PATH_RE).reply((config) => {
      calls += 1;
      const body = JSON.parse(String(config.data ?? '{}'));
      const asked = body.nextSrcSns as number[];
      if (calls === 1) {
        return ok({
          tracked: trackedFor(asked.slice(0, 2)),
          truncated: true,
          resume: { srcSn: asked[1], prevPolygon: RESUME_POLYGON, nextSrcSns: asked.slice(2) },
        });
      }
      return ok({ tracked: trackedFor(asked), truncated: false, resume: null });
    });

    await sam2TrackAllChunks(
      50,
      { trackId: 't-1', prevPolygon: TRIANGLE, label: 'person', nextSrcSns: [51, 52, 53, 54] },
      (done, total) => {
        seen.push(done);
        expect(total).toBe(4);
      },
    );

    // 앞 2건 → 나머지 2건. 결과 목록 크기가 아니라 **처리한 프레임 수**로 올라온다.
    expect(seen).toEqual([2, 4]);
  });

  it('진행_보고는_결과에서_빠진_프레임까지_센다', async () => {
    // 처리했지만 결과 목록에서 빠지는 프레임이 있다 — 결과 개수로 세면 진행이 멈춘 것처럼 보인다.
    const seen: number[] = [];
    let calls = 0;
    mock.onPost(SAM2_PATH_RE).reply((config) => {
      calls += 1;
      const body = JSON.parse(String(config.data ?? '{}'));
      const asked = body.nextSrcSns as number[];
      if (calls === 1) {
        return ok({
          tracked: [], // 두 건을 처리했지만 결과에는 없다
          truncated: true,
          resume: { srcSn: asked[1], prevPolygon: RESUME_POLYGON, nextSrcSns: asked.slice(2) },
        });
      }
      return ok({ tracked: trackedFor(asked), truncated: false, resume: null });
    });

    await sam2TrackAllChunks(
      60,
      { trackId: 't-1', prevPolygon: TRIANGLE, label: 'person', nextSrcSns: [61, 62, 63] },
      (done) => seen.push(done),
    );

    expect(seen).toEqual([2, 3]);
  });

  it('mock으로_시드가_끊기면_남은_조각_수를_알린다', async () => {
    // given: 60프레임(50 + 10). 첫 조각이 mock 으로 통째 제외돼 이어붙일 폴리곤이 없다.
    const next = Array.from({ length: 60 }, (_, i) => 200 + i);
    mock.onPost(SAM2_PATH_RE).reply(() =>
      ok({ tracked: [], truncated: false, resume: null }, 'AI 모델 미로드 — 결과 신뢰 불가'),
    );

    const res = await sam2TrackAllChunks(100, {
      trackId: 't-1',
      prevPolygon: TRIANGLE,
      label: 'person',
      nextSrcSns: next,
    });

    // 뒤 조각 10건이 통째로 남았다 — 0 으로 알리면 «다 했다» 로 읽힌다.
    expect(res.unprocessed).toBe(10);
    expect(res.truncated).toBe(true);
    expect(res.resume).toBeNull();
  });

  it('조각_크기는_서버_예산을_따라간다', () => {
    // 서버가 프레임당 몫을 0 으로 내리면 화면은 서버 상한까지 한 번에 싣는다 — 고쳐야 하는 것은
    // 서버 값이지 화면 상수가 아니다.
    publishAiWaitBudgets({ sam2Track: { baseSec: 252, perFrameSec: 0, ceilingSec: 300 } });
    expect(aiWaitChunkSize('sam2Track', SAM2_TRACK_MAX_FRAMES_PER_REQUEST)).toBe(50);
  });

  it('서버_값을_못_받아도_조각_크기가_서버_상한까지_간다', () => {
    // 폴백이 서버의 도출 규칙과 갈리면 «못 받았을 때만 잘게 쪼개는» 차이가 조용히 생긴다.
    resetAiWaitBudgets();
    expect(aiWaitChunkSize('sam2Track', SAM2_TRACK_MAX_FRAMES_PER_REQUEST)).toBe(50);
  });
});

describe('AI 자동 추적 — 잘린 응답 이어 보내기', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    publishAiWaitBudgets({ autoTrack: { baseSec: 0, perFrameSec: 0, ceilingSec: 100_000 } });
  });

  afterEach(() => {
    mock.restore();
    resetAiWaitBudgets();
  });

  /** 프레임 하나의 검출 1건 — 트래커 객체 ID 를 지정할 수 있다. */
  function frame(srcSn: number, frameIndex: number, trackId: number | null) {
    return {
      srcSn,
      frameIndex,
      detections: [
        { label: 'person', points: [0, 0, 10, 10], score: 0.9, trackId, labelId: 7 },
      ],
    };
  }

  it('잘린_응답이_오면_남은_프레임을_이어_보내_검출이_유실되지_않는다', async () => {
    const seen: { path: number; body: Record<string, unknown> }[] = [];
    mock.onPost(YOLO_PATH_RE).reply((config) => {
      const path = Number(YOLO_PATH_RE.exec(config.url ?? '')![1]);
      const body = JSON.parse(String(config.data ?? '{}'));
      seen.push({ path, body });
      if (seen.length === 1) {
        // 시작 프레임(100) + 101 까지 처리하고 102·103 이 남았다
        return ok({
          frames: [frame(100, 0, 1), frame(101, 1, 1)],
          truncated: true,
          resume: { srcSn: 102, nextSrcSns: [103] },
        });
      }
      return ok({
        frames: [frame(102, 0, 1), frame(103, 1, 1)],
        truncated: false,
        resume: null,
      });
    });

    const res = await requestAutoTrackAll(100, [101, 102, 103]);

    expect(seen).toHaveLength(2);
    expect(seen[1].path).toBe(102);
    expect(seen[1].body.srcSn).toBe(102);
    expect(seen[1].body.nextSrcSns).toEqual([103]);
    expect((res.frames ?? []).map((f) => f.srcSn)).toEqual([100, 101, 102, 103]);
    expect(res.truncated).toBe(false);
  });

  it('이어_보낸_조각의_객체는_앞_조각과_한_트랙으로_합쳐지지_않는다', async () => {
    // ★ 요청마다 트래커가 리셋돼 객체 번호가 다시 매겨진다 — 번호가 같다고 같은 객체가 아니다.
    //   합치면 서로 다른 객체가 한 트랙이 되어 그대로 저장된다.
    let calls = 0;
    mock.onPost(YOLO_PATH_RE).reply(() => {
      calls += 1;
      if (calls === 1) {
        return ok({
          frames: [frame(200, 0, 1)],
          truncated: true,
          resume: { srcSn: 201, nextSrcSns: [] },
        });
      }
      return ok({ frames: [frame(201, 0, 1)], truncated: false, resume: null });
    });

    const res = await requestAutoTrackAll(200, [201]);
    const review = buildAutoTrackReview(res, new Map([[200, 0], [201, 1]]));

    // 두 조각의 «1번» 은 서로 다른 묶음이어야 한다
    expect(review.groups).toHaveLength(2);
    const ids = review.groups.map((g) => g.trackId);
    expect(new Set(ids).size).toBe(2);
  });

  it('진행이_0_인_응답에는_같은_요청을_다시_보내지_않는다', async () => {
    let calls = 0;
    mock.onPost(YOLO_PATH_RE).reply((config) => {
      calls += 1;
      const body = JSON.parse(String(config.data ?? '{}'));
      return ok({
        frames: [],
        truncated: true,
        resume: { srcSn: body.srcSn as number, nextSrcSns: body.nextSrcSns as number[] },
      });
    });

    const res = await requestAutoTrackAll(300, [301, 302]);

    expect(calls).toBe(1);
    expect(res.truncated).toBe(true);
    // 못 한 구간을 그대로 알린다 — 시작 프레임까지 포함해 «남은 3건»
    expect(res.resume?.srcSn).toBe(300);
    expect(res.resume?.nextSrcSns).toEqual([301, 302]);
  });

  it('취소하면_다음_조각을_보내지_않는다', async () => {
    const controller = new AbortController();
    let calls = 0;
    mock.onPost(YOLO_PATH_RE).reply(() => {
      calls += 1;
      controller.abort();
      return ok({
        frames: [frame(400, 0, 1)],
        truncated: true,
        resume: { srcSn: 401, nextSrcSns: [402] },
      });
    });

    await requestAutoTrackAll(400, [401, 402], { signal: controller.signal }).catch(
      () => undefined,
    );

    expect(calls).toBe(1);
  });

  it('이어_보낼_때마다_진행이_올라온다', async () => {
    const seen: { done: number; total: number }[] = [];
    let calls = 0;
    mock.onPost(YOLO_PATH_RE).reply(() => {
      calls += 1;
      if (calls === 1) {
        return ok({
          frames: [frame(500, 0, 1), frame(501, 1, 1)],
          truncated: true,
          resume: { srcSn: 502, nextSrcSns: [503] },
        });
      }
      return ok({
        frames: [frame(502, 0, 1), frame(503, 1, 1)],
        truncated: false,
        resume: null,
      });
    });

    await requestAutoTrackAll(500, [501, 502, 503], {
      onProgress: (done, total) => seen.push({ done, total }),
    });

    // 시퀀스는 [시작 + 후속] 4건이다
    expect(seen).toEqual([
      { done: 2, total: 4 },
      { done: 4, total: 4 },
    ]);
  });
});
