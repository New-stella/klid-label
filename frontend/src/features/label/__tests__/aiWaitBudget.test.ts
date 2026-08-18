// AI 대기 예산 — **서버가 준 값**으로 요청 제한시간·분할 단위·작업잠금 상한을 정한다.
//
// 이 파일이 고정하는 계약:
//  1. 예산의 진실원은 서버다(`GET /v1/ai-defaults`). 화면은 그 값을 자기 상수로 들고 있지 않는다 —
//     들고 있으면 서버의 재시도 예산이 바뀔 때 화면만 조용히 어긋난다.
//  2. 계산식은 `baseSec + perFrameSec × 프레임수` 이고 `ceilingSec` 로 자른다.
//  3. 한 요청이 ceiling 을 넘으면 **기다리지 않고 나눠 보낸다** — 중간에 끊길 때 완료분까지
//     통째로 버려지는 것을 막는다(경로 중 가장 작은 상한이 실제 상한이므로).
//  4. 작업 종류마다 자기 예산을 쓴다 — 공용 상수 하나를 공유하면 올리면 전역 완화, 두면 추적이 죽는다.
//  5. 서버 값을 못 받았거나 비정상이면 **충분히 긴 폴백**으로 간다. 짧은 폴백은 원래 결함
//     (정상 동작이 «AI 실패» 로 보이는 것)을 그대로 되돌린다.
//
// ⚠ 값 단언은 «기본값과 다르다» 가 아니라 **기대값과 같다**로 쓴다. 다르다-단언은 값이 우연히
//   겹치는 순간 조용히 참이 되어 아무것도 지키지 못한다.

import { afterEach, describe, expect, it } from 'vitest';

import {
  AI_WAIT_BUDGET_FALLBACK,
  AI_WAIT_BUSY_GRACE_MS,
  aiWaitBudget,
  aiWaitBusyMaxMs,
  aiWaitChunkSize,
  aiWaitTimeoutMs,
  busyKindWaitKind,
  publishAiWaitBudgets,
  resetAiWaitBudgets,
  type AiWaitKind,
} from '../aiBudget';

const ALL_KINDS: AiWaitKind[] = ['autolabel', 'segment', 'sam2Track', 'autoTrack'];

afterEach(() => {
  resetAiWaitBudgets();
});

describe('AI 대기 예산 — 서버 값이 진실원', () => {
  it('서버가_준_예산으로_제한시간을_계산한다', () => {
    // given: 서버가 track 예산을 내려줬다
    publishAiWaitBudgets({ sam2Track: { baseSec: 10, perFrameSec: 2, ceilingSec: 600 } });

    // when / then: base + perFrame × 프레임수
    expect(aiWaitTimeoutMs('sam2Track', 5)).toBe((10 + 2 * 5) * 1000);
  });

  it('서버가_준_상한으로_자른다', () => {
    // given
    publishAiWaitBudgets({ sam2Track: { baseSec: 10, perFrameSec: 2, ceilingSec: 30 } });

    // when: 계산값 10 + 2×100 = 210초 > 상한 30초
    // then: 상한으로 잘린다
    expect(aiWaitTimeoutMs('sam2Track', 100)).toBe(30 * 1000);
  });

  it('서버_예산이_바뀌면_화면_상수를_고치지_않아도_따라간다', () => {
    // 이 과제의 존재 이유 — 화면이 값을 들고 있으면 서버가 예산을 바꿔도 조용히 어긋난다.
    publishAiWaitBudgets({ autolabel: { baseSec: 11, perFrameSec: 0, ceilingSec: 900 } });
    expect(aiWaitTimeoutMs('autolabel')).toBe(11_000);

    publishAiWaitBudgets({ autolabel: { baseSec: 22, perFrameSec: 0, ceilingSec: 900 } });
    expect(aiWaitTimeoutMs('autolabel')).toBe(22_000);
  });

  it('네_작업_종류가_각자_자기_예산을_쓴다', () => {
    // given: 종류마다 다른 예산
    publishAiWaitBudgets({
      autolabel: { baseSec: 1, perFrameSec: 0, ceilingSec: 900 },
      segment: { baseSec: 2, perFrameSec: 0, ceilingSec: 900 },
      sam2Track: { baseSec: 3, perFrameSec: 0, ceilingSec: 900 },
      autoTrack: { baseSec: 4, perFrameSec: 0, ceilingSec: 900 },
    });

    // then: 하나의 공용 값으로 뭉개지지 않는다
    expect(aiWaitTimeoutMs('autolabel')).toBe(1_000);
    expect(aiWaitTimeoutMs('segment')).toBe(2_000);
    expect(aiWaitTimeoutMs('sam2Track')).toBe(3_000);
    expect(aiWaitTimeoutMs('autoTrack')).toBe(4_000);
  });

  it('라벨링_작업_종류가_예산_종류로_매핑된다', () => {
    expect(busyKindWaitKind('AI_DETECT')).toBe('autolabel');
    expect(busyKindWaitKind('AI_SEGMENT')).toBe('segment');
    expect(busyKindWaitKind('AI_TRACK')).toBe('sam2Track');
    expect(busyKindWaitKind('AI_AUTO_TRACK')).toBe('autoTrack');
    // AI 가 아닌 작업은 예산 축이 없다 — 억지로 매핑하면 없는 계약을 만든 것이 된다.
    expect(busyKindWaitKind('SAVE')).toBeNull();
    expect(busyKindWaitKind('LOAD')).toBeNull();
  });
});

describe('AI 대기 예산 — 서버 값을 못 받았을 때', () => {
  it('서버_값이_없으면_폴백_예산을_쓴다', () => {
    // given: 조회 실패·응답 생략 → 아무 것도 발행되지 않은 상태
    // then: 종류별 폴백 그대로
    for (const kind of ALL_KINDS) {
      expect(aiWaitBudget(kind)).toEqual(AI_WAIT_BUDGET_FALLBACK[kind]);
    }
  });

  it('폴백이_서버의_도출_규칙과_같은_값을_쓴다', () => {
    // ★ 폴백이 서버와 다른 계산에서 나오면 «서버 값을 못 받았을 때만 동작이 달라지는» 재현하기
    //   어려운 차이가 생긴다. 재료는 전부 서버 설정에서 나온다:
    //     호출 1회 최악 183초(=60초 × 3회 + 백오프 1·2초) · 온라인 블로킹 상한 70초 ·
    //     폴리곤 배치 예산 60초 · 고정 부대비용 10초 · 프레임당 부대비용 2초
    const worstCall = 183;
    const onlineBlock = 70;
    const polygonBatch = 60;
    const trackBatch = 240;
    const fixedOverhead = 10;
    const perFrameOverhead = 2;

    // 오토라벨은 스스로 70초에 잘라 183초까지 가지 않는다.
    expect(AI_WAIT_BUDGET_FALLBACK.autolabel).toEqual({
      baseSec: onlineBlock + polygonBatch + fixedOverhead,
      perFrameSec: 0,
      ceilingSec: 300,
    });
    // 분할은 상한 없는 대기라 재시도 체인이 통째로 돈다.
    expect(AI_WAIT_BUDGET_FALLBACK.segment).toEqual({
      baseSec: worstCall + fixedOverhead,
      perFrameSec: 0,
      ceilingSec: 300,
    });
    // ★ 프레임 순회 두 경로는 **요청 단위 시간 예산**이 프레임 수를 흡수한다 — 그래서 가산분이
    //   0 이고, 예산이 다하면 서버가 «어디까지 했는지»를 돌려줘 화면이 그 지점부터 이어 보낸다.
    //   가산분을 옛 값(프레임당 재시도 체인)으로 되돌리면 50프레임이 50번으로 쪼개진다.
    expect(AI_WAIT_BUDGET_FALLBACK.sam2Track).toEqual({
      baseSec: fixedOverhead + trackBatch + perFrameOverhead,
      perFrameSec: 0,
      ceilingSec: 300,
    });
    expect(AI_WAIT_BUDGET_FALLBACK.autoTrack).toEqual({
      baseSec: fixedOverhead + trackBatch + perFrameOverhead,
      perFrameSec: 0,
      ceilingSec: 300,
    });
  });

  it('폴백이_공용_기본값보다_짧아지지_않는다', () => {
    // ★ 짧은 폴백은 원래 결함을 그대로 되돌린다 — 정상 동작이 «AI 실패» 로 보이는 그 상태다.
    //   공용 기본값(30초)으로 떨어지는 종류가 하나도 없어야 한다.
    for (const kind of ALL_KINDS) {
      expect(aiWaitTimeoutMs(kind, 1)).toBeGreaterThan(30_000);
    }
  });

  it('일부_종류만_내려오면_나머지는_폴백을_유지한다', () => {
    // given: 서버가 track 만 보냈다
    publishAiWaitBudgets({ sam2Track: { baseSec: 7, perFrameSec: 1, ceilingSec: 900 } });

    // then: 받은 것은 서버 값, 못 받은 것은 폴백
    expect(aiWaitTimeoutMs('sam2Track', 3)).toBe(10_000);
    expect(aiWaitBudget('autolabel')).toEqual(AI_WAIT_BUDGET_FALLBACK.autolabel);
  });

  it('필드_하나만_내려오면_나머지_필드도_폴백을_유지한다', () => {
    // 부분 응답이 «나머지는 0» 으로 해석되면 제한시간이 0(=즉시 끊김)이 된다.
    publishAiWaitBudgets({ autolabel: { baseSec: 300 } });
    expect(aiWaitBudget('autolabel')).toEqual({
      ...AI_WAIT_BUDGET_FALLBACK.autolabel,
      baseSec: 300,
    });
  });

  it('비정상_값은_받지_않고_폴백으로_간다', () => {
    // 0·음수·NaN·무한대·문자열은 각각 즉시 끊김/무제한/NaN 제한시간이라 전부 사고다.
    for (const bad of [0, -1, Number.NaN, Number.POSITIVE_INFINITY, '90' as unknown as number]) {
      publishAiWaitBudgets({ autolabel: { ceilingSec: bad } });
      expect(aiWaitBudget('autolabel').ceilingSec).toBe(
        AI_WAIT_BUDGET_FALLBACK.autolabel.ceilingSec,
      );
    }
    // baseSec 은 0 이 정당하다(고정 비용 없음). 음수·비유한만 배제한다.
    publishAiWaitBudgets({ autolabel: { baseSec: 0, perFrameSec: 5, ceilingSec: 900 } });
    expect(aiWaitBudget('autolabel').baseSec).toBe(0);
  });

  it('상한이_고정분보다_작게_내려와도_제한시간이_0이_되지_않는다', () => {
    publishAiWaitBudgets({ autolabel: { baseSec: 100, perFrameSec: 0, ceilingSec: 1 } });
    expect(aiWaitTimeoutMs('autolabel')).toBeGreaterThan(0);
  });

  it('프레임_수가_비정상이면_0건으로_친다', () => {
    publishAiWaitBudgets({ sam2Track: { baseSec: 10, perFrameSec: 2, ceilingSec: 900 } });
    for (const bad of [Number.NaN, -5, Number.POSITIVE_INFINITY]) {
      expect(aiWaitTimeoutMs('sam2Track', bad)).toBe(10_000);
    }
  });
});

describe('AI 대기 예산 — 상한을 넘으면 나눠 보낸다', () => {
  it('상한_안에_들어가는_프레임_수를_분할_단위로_준다', () => {
    // given: 고정 10초 + 프레임당 20초, 상한 90초 → (90-10)/20 = 4건
    publishAiWaitBudgets({ sam2Track: { baseSec: 10, perFrameSec: 20, ceilingSec: 90 } });

    // then
    expect(aiWaitChunkSize('sam2Track', 50)).toBe(4);
    // 그 단위로 보낸 요청은 상한을 넘지 않는다 — 이것이 분할의 목적이다.
    expect(aiWaitTimeoutMs('sam2Track', 4)).toBeLessThanOrEqual(90 * 1000);
  });

  it('분할_단위는_서버_상한을_넘지_않는다', () => {
    // 서버가 요청당 프레임 수를 제한한다(@Size(max=50)) — 그보다 크게 나눠 보내면 400 이다.
    publishAiWaitBudgets({ sam2Track: { baseSec: 0, perFrameSec: 1, ceilingSec: 100_000 } });
    expect(aiWaitChunkSize('sam2Track', 50)).toBe(50);
  });

  it('프레임당_비용이_0이면_나눌_이유가_없어_서버_상한까지_보낸다', () => {
    publishAiWaitBudgets({ sam2Track: { baseSec: 60, perFrameSec: 0, ceilingSec: 90 } });
    expect(aiWaitChunkSize('sam2Track', 50)).toBe(50);
  });

  it('한_건도_안_들어가는_예산이어도_최소_1건은_보낸다', () => {
    // 0 을 돌려주면 무한 루프이거나 «아무 것도 안 보냄» 이 된다.
    publishAiWaitBudgets({ sam2Track: { baseSec: 100, perFrameSec: 100, ceilingSec: 101 } });
    expect(aiWaitChunkSize('sam2Track', 50)).toBe(1);
  });
});

describe('AI 대기 예산 — 작업잠금 상한', () => {
  it('작업잠금_상한은_요청_제한시간보다_길다', () => {
    // ★ 순서가 뒤집히면 요청이 성공해도 결과가 **조용히 폐기**되어 사용자는 성공도 실패도 못 본다.
    //   제한시간이 먼저 터져야 «실패했다» 는 안내가 뜬다.
    publishAiWaitBudgets({ autolabel: { baseSec: 200, perFrameSec: 0, ceilingSec: 200 } });
    expect(aiWaitBusyMaxMs('AI_DETECT')).toBeGreaterThan(aiWaitTimeoutMs('autolabel'));
    expect(aiWaitBusyMaxMs('AI_DETECT')).toBe(200_000 + AI_WAIT_BUSY_GRACE_MS);
  });

  it('나눠_보내는_작업의_잠금_상한은_전체_묶음을_덮는다', () => {
    // 분할 전송은 요청 하나가 아니라 **묶음 전체**가 잠금 구간이다. 요청 하나 기준으로 잡으면
    // 두 번째 청크에서 잠금이 먼저 풀려 결과가 조용히 폐기된다.
    publishAiWaitBudgets({ sam2Track: { baseSec: 10, perFrameSec: 20, ceilingSec: 90 } });
    // 12건 → 분할 단위 4 → 3묶음 × 90초
    expect(aiWaitBusyMaxMs('AI_TRACK', 12, 50)).toBe(3 * 90_000 + AI_WAIT_BUSY_GRACE_MS);
  });

  it('AI가_아닌_작업은_종전_공용_상한을_그대로_쓴다', () => {
    // 저장·불러오기는 추론 예산 축이 아니다 — 억지로 끌어오면 없는 계약이 생긴다.
    expect(aiWaitBusyMaxMs('SAVE')).toBe(5 * 60 * 1000);
    expect(aiWaitBusyMaxMs('LOAD')).toBe(5 * 60 * 1000);
  });

  it('종류마다_잠금_상한이_갈린다', () => {
    // 공용 5분 하나를 나눈 것이 이 과제다 — 한 값으로 되돌아가면 추적이 다시 죽는다.
    publishAiWaitBudgets({
      autolabel: { baseSec: 100, perFrameSec: 0, ceilingSec: 100 },
      autoTrack: { baseSec: 200, perFrameSec: 0, ceilingSec: 200 },
    });
    expect(aiWaitBusyMaxMs('AI_DETECT')).toBe(100_000 + AI_WAIT_BUSY_GRACE_MS);
    expect(aiWaitBusyMaxMs('AI_AUTO_TRACK')).toBe(200_000 + AI_WAIT_BUSY_GRACE_MS);
  });
});
