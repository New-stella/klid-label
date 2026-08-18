// AI 추론 4경로(오토라벨 · 분할 · SAM2 추적 · AI 자동 추적) — 요청 제한시간·분할·중단 배선.
//
// 이 파일이 고정하는 계약:
//  - 네 경로 모두 공용 기본값(30초)을 쓰지 않고 **서버가 준 대기 예산**을 쓴다. 값의 계산·폴백은
//    `aiBudget.ts` 가 판정하며 여기서는 «그 판정 결과가 실제 요청에 실리는가» 만 본다.
//    ⚠ 제한시간 숫자를 이 파일에 리터럴로 적지 않는다 — 적는 순간 그 숫자가 두 번째 진실원이 되어
//      서버 예산이 바뀌어도 테스트만 통과하는 상태가 된다.
//  - 프레임을 훑는 경로는 **프레임 수에 비례**한다 — 서버가 요청 안에서 프레임마다 추론을 한 번씩
//    돌리므로 고정값을 주면 프레임이 많을 때 모자라고 적을 때 과하다.
//  - 예산 상한을 넘는 추적은 **기다리지 않고 나눠 보낸다**(완료분 보존 + 진행 표시 갱신).
//  - 취소 신호(AbortSignal)를 요청에 실어 보낸다 — 취소가 화면 안에서만 끝나면 서버는 계속 돌며
//    자원을 물고 있는다.
//
// ⚠ 값 단언은 **기대값과 같다**로 쓴다. 「기본값과 다르다」로 쓰면 값이 우연히 겹치는 순간
//   조용히 참이 되어 아무것도 지키지 못한다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import {
  aiWaitChunkSize,
  aiWaitTimeoutMs,
  publishAiWaitBudgets,
  resetAiWaitBudgets,
} from '../aiBudget';
import {
  SAM2_TRACK_MAX_FRAMES_PER_REQUEST,
  requestAutolabel,
  requestSam2Segment,
  requestSam2Track,
  sam2TrackAllChunks,
} from '../api';
import { AUTO_TRACK_MAX_NEXT_FRAMES, requestAutoTrack } from '../api/autoTrack';

describe('AI 추론 — 요청 제한시간(단일 프레임 경로)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    resetAiWaitBudgets();
  });

  it('오토라벨_요청에_서버가_준_대기_예산이_실린다', async () => {
    // given: 서버가 오토라벨 예산을 내려줬다
    publishAiWaitBudgets({ autolabel: { baseSec: 137, perFrameSec: 0, ceilingSec: 600 } });
    let seenTimeout: number | undefined;
    mock.onPost('/frames/7/autolabel').reply((config) => {
      seenTimeout = config.timeout;
      return [200, { success: true, data: { srcSn: 7, savedCount: 0, labels: [] }, errorCode: null }];
    });

    // when
    await requestAutolabel(7);

    // then: 화면 상수가 아니라 서버 예산이 실린다
    expect(seenTimeout).toBe(aiWaitTimeoutMs('autolabel'));
    expect(seenTimeout).toBe(137_000);
  });

  it('오토라벨_요청은_검출옵션을_실어도_같은_제한시간을_쓴다', async () => {
    // given: body 가 있는 호출 분기(classes/shape/정밀도)도 같은 예산이어야 한다
    let seenTimeout: number | undefined;
    mock.onPost('/frames/7/autolabel').reply((config) => {
      seenTimeout = config.timeout;
      return [200, { success: true, data: { srcSn: 7, savedCount: 0, labels: [] }, errorCode: null }];
    });

    // when
    await requestAutolabel(7, ['person'], 'POLYGON', { confThreshold: 0.5 });

    // then
    expect(seenTimeout).toBe(aiWaitTimeoutMs('autolabel'));
  });

  it('분할_요청에_서버가_준_대기_예산이_실린다', async () => {
    // given
    publishAiWaitBudgets({ segment: { baseSec: 91, perFrameSec: 0, ceilingSec: 600 } });
    let seenTimeout: number | undefined;
    mock.onPost('/frames/7/sam2-segment').reply((config) => {
      seenTimeout = config.timeout;
      return [200, { success: true, data: { polygon: [], score: 0 }, errorCode: null }];
    });

    // when
    await requestSam2Segment(7, { box: [1, 2, 3, 4] });

    // then
    expect(seenTimeout).toBe(91_000);
  });

  it('오토라벨과_분할은_서로_다른_예산을_쓴다', async () => {
    // 공용 상수 하나를 나눈 것이 이 라운드다 — 한 값으로 되돌아가면 종류별 조정이 불가능해진다.
    publishAiWaitBudgets({
      autolabel: { baseSec: 111, perFrameSec: 0, ceilingSec: 600 },
      segment: { baseSec: 222, perFrameSec: 0, ceilingSec: 600 },
    });
    const seen: Record<string, number | undefined> = {};
    mock.onPost('/frames/7/autolabel').reply((c) => {
      seen.autolabel = c.timeout;
      return [200, { success: true, data: { srcSn: 7, savedCount: 0, labels: [] }, errorCode: null }];
    });
    mock.onPost('/frames/7/sam2-segment').reply((c) => {
      seen.segment = c.timeout;
      return [200, { success: true, data: { polygon: [], score: 0 }, errorCode: null }];
    });

    await requestAutolabel(7);
    await requestSam2Segment(7, { box: [1, 2, 3, 4] });

    expect(seen.autolabel).toBe(111_000);
    expect(seen.segment).toBe(222_000);
  });
});

describe('AI 추론 — 요청 제한시간(프레임 순회 경로)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    resetAiWaitBudgets();
  });

  it('SAM2_추적_요청에_프레임_수로_계산한_예산이_실린다', async () => {
    // given
    publishAiWaitBudgets({ sam2Track: { baseSec: 10, perFrameSec: 5, ceilingSec: 600 } });
    let seenTimeout: number | undefined;
    mock.onPost('/frames/7/sam2-track').reply((config) => {
      seenTimeout = config.timeout;
      return [200, { success: true, data: { tracked: [] }, errorCode: null }];
    });

    // when: 후속 프레임 3개
    await requestSam2Track(7, {
      trackId: 't1',
      prevPolygon: [
        [0, 0],
        [1, 0],
        [1, 1],
      ],
      label: '사람',
      nextSrcSns: [8, 9, 10],
    });

    // then: 10 + 5×3
    expect(seenTimeout).toBe(aiWaitTimeoutMs('sam2Track', 3));
    expect(seenTimeout).toBe(25_000);
  });

  it('AI_자동_추적_요청에도_프레임_수로_계산한_예산이_실린다', async () => {
    // ★ 이 경로는 종전에 제한시간을 전혀 싣지 않아 공용 기본값(30초)에 걸려 있었다 —
    //   50프레임 구간이 정상 처리 중에도 «AI 실패» 로 보이던 자리다.
    publishAiWaitBudgets({ autoTrack: { baseSec: 20, perFrameSec: 4, ceilingSec: 600 } });
    let seenTimeout: number | undefined;
    mock.onPost('/frames/7/yolo-track').reply((config) => {
      seenTimeout = config.timeout;
      return [200, { success: true, data: { frames: [] }, errorCode: null }];
    });

    // when
    await requestAutoTrack(7, [8, 9, 10, 11, 12]);

    // then: 20 + 4×5
    expect(seenTimeout).toBe(aiWaitTimeoutMs('autoTrack', 5));
    expect(seenTimeout).toBe(40_000);
  });

  it('AI_자동_추적은_서버_상한만큼만_싣고_그_수로_예산을_계산한다', async () => {
    // 잘려 나갈 프레임까지 예산에 넣으면 실제로 보내지 않는 일에 제한시간을 준다.
    publishAiWaitBudgets({ autoTrack: { baseSec: 0, perFrameSec: 1, ceilingSec: 10_000 } });
    let seenTimeout: number | undefined;
    let seenCount = 0;
    mock.onPost('/frames/7/yolo-track').reply((config) => {
      seenTimeout = config.timeout;
      seenCount = (JSON.parse(String(config.data)) as { nextSrcSns: number[] }).nextSrcSns.length;
      return [200, { success: true, data: { frames: [] }, errorCode: null }];
    });

    const tooMany = Array.from({ length: AUTO_TRACK_MAX_NEXT_FRAMES + 30 }, (_, i) => 100 + i);
    await requestAutoTrack(7, tooMany);

    expect(seenCount).toBe(AUTO_TRACK_MAX_NEXT_FRAMES);
    expect(seenTimeout).toBe(aiWaitTimeoutMs('autoTrack', AUTO_TRACK_MAX_NEXT_FRAMES));
  });

  it('추적_예산은_상한으로_잘린다', async () => {
    // 상한을 넘겨 계산하면 제한시간이 «사실상 무제한» 이 되어 상한을 둔 의미가 사라진다.
    publishAiWaitBudgets({ sam2Track: { baseSec: 10, perFrameSec: 100, ceilingSec: 45 } });
    let seenTimeout: number | undefined;
    mock.onPost('/frames/7/sam2-track').reply((config) => {
      seenTimeout = config.timeout;
      return [200, { success: true, data: { tracked: [] }, errorCode: null }];
    });

    await requestSam2Track(7, {
      trackId: 't1',
      prevPolygon: [
        [0, 0],
        [1, 0],
        [1, 1],
      ],
      label: '사람',
      nextSrcSns: [8, 9, 10, 11, 12],
    });

    expect(seenTimeout).toBe(45_000);
  });
});

describe('AI 추론 — 상한을 넘으면 나눠 보낸다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    resetAiWaitBudgets();
  });

  it('추적은_예산_상한에_들어가는_단위로_쪼개_보낸다', async () => {
    // given: 프레임당 20초 · 상한 90초 · 고정 10초 → 한 요청에 4건
    publishAiWaitBudgets({ sam2Track: { baseSec: 10, perFrameSec: 20, ceilingSec: 90 } });
    const sentCounts: number[] = [];
    mock.onPost(/\/frames\/\d+\/sam2-track/).reply((config) => {
      const body = JSON.parse(String(config.data)) as { nextSrcSns: number[] };
      sentCounts.push(body.nextSrcSns.length);
      return [
        200,
        {
          success: true,
          data: {
            tracked: body.nextSrcSns.map((sn) => ({
              srcSn: sn,
              points: [
                [0, 0],
                [1, 0],
                [1, 1],
              ],
            })),
          },
          errorCode: null,
        },
      ];
    });

    // when: 10건 추적
    const res = await sam2TrackAllChunks(7, {
      trackId: 't1',
      prevPolygon: [
        [0, 0],
        [1, 0],
        [1, 1],
      ],
      label: '사람',
      nextSrcSns: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
    });

    // then: 4 + 4 + 2 로 나뉘고 결과는 모두 이어붙는다
    expect(aiWaitChunkSize('sam2Track', SAM2_TRACK_MAX_FRAMES_PER_REQUEST)).toBe(4);
    expect(sentCounts).toEqual([4, 4, 2]);
    expect(res.tracked).toHaveLength(10);
  });

  it('나눠_보낸_단위마다_진행이_갱신된다', async () => {
    // 분할의 부수 효과 — 진행 표시가 요청 하나가 끝날 때마다 올라온다.
    publishAiWaitBudgets({ sam2Track: { baseSec: 0, perFrameSec: 10, ceilingSec: 30 } });
    mock.onPost(/\/frames\/\d+\/sam2-track/).reply((config) => {
      const body = JSON.parse(String(config.data)) as { nextSrcSns: number[] };
      return [
        200,
        {
          success: true,
          data: {
            tracked: body.nextSrcSns.map((sn) => ({
              srcSn: sn,
              points: [
                [0, 0],
                [1, 0],
                [1, 1],
              ],
            })),
          },
          errorCode: null,
        },
      ];
    });

    const progress: number[] = [];
    await sam2TrackAllChunks(
      7,
      {
        trackId: 't1',
        prevPolygon: [
          [0, 0],
          [1, 0],
          [1, 1],
        ],
        label: '사람',
        nextSrcSns: [1, 2, 3, 4, 5, 6],
      },
      (done) => progress.push(done),
    );

    // 3건씩 두 묶음 → 진행이 두 번 올라온다(예산 상한이 컸다면 한 번뿐이다)
    expect(progress).toEqual([3, 6]);
  });

  it('예산이_넉넉하면_서버_상한까지_한_번에_보낸다', async () => {
    // 나누는 것 자체가 목적이 아니다 — 상한 안에 들어가면 왕복을 늘리지 않는다.
    publishAiWaitBudgets({ sam2Track: { baseSec: 0, perFrameSec: 1, ceilingSec: 100_000 } });
    const sentCounts: number[] = [];
    mock.onPost(/\/frames\/\d+\/sam2-track/).reply((config) => {
      const body = JSON.parse(String(config.data)) as { nextSrcSns: number[] };
      sentCounts.push(body.nextSrcSns.length);
      return [
        200,
        {
          success: true,
          data: {
            tracked: body.nextSrcSns.map((sn) => ({
              srcSn: sn,
              points: [
                [0, 0],
                [1, 0],
                [1, 1],
              ],
            })),
          },
          errorCode: null,
        },
      ];
    });

    await sam2TrackAllChunks(7, {
      trackId: 't1',
      prevPolygon: [
        [0, 0],
        [1, 0],
        [1, 1],
      ],
      label: '사람',
      nextSrcSns: [1, 2, 3, 4, 5, 6, 7, 8],
    });

    expect(sentCounts).toEqual([8]);
  });

  it('분할_단위는_서버가_받는_프레임_상한을_넘지_않는다', () => {
    // 서버 `@Size(max=50)` 초과로 나눠 보내면 400 이다 — 나눔이 오히려 요청을 깨뜨린다.
    publishAiWaitBudgets({ sam2Track: { baseSec: 0, perFrameSec: 0.0001, ceilingSec: 100_000 } });
    expect(aiWaitChunkSize('sam2Track', SAM2_TRACK_MAX_FRAMES_PER_REQUEST)).toBe(
      SAM2_TRACK_MAX_FRAMES_PER_REQUEST,
    );
  });
});

describe('AI 추론 — 취소 신호 전달', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    resetAiWaitBudgets();
  });

  it('네_경로_모두_받은_중단_신호를_요청에_실어_보낸다', async () => {
    // 취소가 화면 안에서만 끝나면 서버는 계속 돌며 추론 자원을 물고 있는다.
    const controller = new AbortController();
    const seen: Record<string, AbortSignal | undefined> = {};
    mock.onPost('/frames/7/autolabel').reply((c) => {
      seen.autolabel = c.signal as AbortSignal | undefined;
      return [200, { success: true, data: { srcSn: 7, savedCount: 0, labels: [] }, errorCode: null }];
    });
    mock.onPost('/frames/7/sam2-segment').reply((c) => {
      seen.segment = c.signal as AbortSignal | undefined;
      return [200, { success: true, data: { polygon: [], score: 0 }, errorCode: null }];
    });
    mock.onPost('/frames/7/sam2-track').reply((c) => {
      seen.track = c.signal as AbortSignal | undefined;
      return [200, { success: true, data: { tracked: [] }, errorCode: null }];
    });
    mock.onPost('/frames/7/yolo-track').reply((c) => {
      seen.autoTrack = c.signal as AbortSignal | undefined;
      return [200, { success: true, data: { frames: [] }, errorCode: null }];
    });

    await requestAutolabel(7, [], undefined, undefined, controller.signal);
    await requestSam2Segment(7, { box: [1, 2, 3, 4] }, controller.signal);
    await requestSam2Track(
      7,
      {
        trackId: 't1',
        prevPolygon: [
          [0, 0],
          [1, 0],
          [1, 1],
        ],
        label: '사람',
        nextSrcSns: [8],
      },
      controller.signal,
    );
    await requestAutoTrack(7, [8], controller.signal);

    expect(seen.autolabel).toBe(controller.signal);
    expect(seen.segment).toBe(controller.signal);
    expect(seen.track).toBe(controller.signal);
    expect(seen.autoTrack).toBe(controller.signal);
  });

  it('나눠_보내는_추적은_모든_조각에_중단_신호를_싣는다', async () => {
    // 한 조각만 신호를 실으면 나머지 조각이 취소 뒤에도 서버에서 계속 돈다.
    publishAiWaitBudgets({ sam2Track: { baseSec: 0, perFrameSec: 10, ceilingSec: 20 } });
    const controller = new AbortController();
    const signals: (AbortSignal | undefined)[] = [];
    mock.onPost(/\/frames\/\d+\/sam2-track/).reply((config) => {
      signals.push(config.signal as AbortSignal | undefined);
      const body = JSON.parse(String(config.data)) as { nextSrcSns: number[] };
      return [
        200,
        {
          success: true,
          data: {
            tracked: body.nextSrcSns.map((sn) => ({
              srcSn: sn,
              points: [
                [0, 0],
                [1, 0],
                [1, 1],
              ],
            })),
          },
          errorCode: null,
        },
      ];
    });

    await sam2TrackAllChunks(
      7,
      {
        trackId: 't1',
        prevPolygon: [
          [0, 0],
          [1, 0],
          [1, 1],
        ],
        label: '사람',
        nextSrcSns: [1, 2, 3, 4],
      },
      undefined,
      controller.signal,
    );

    expect(signals).toHaveLength(2);
    expect(signals.every((s) => s === controller.signal)).toBe(true);
  });
});
