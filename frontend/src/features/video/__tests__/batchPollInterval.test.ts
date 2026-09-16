import { describe, expect, it } from 'vitest';

import {
  BATCH_POLL_INTERVAL_MS,
  BATCH_PROCESSING_POLL_WINDOW_MS,
  LEAD_DEIDENT_ACCEPT_POLL_WINDOW_MS,
  batchPollInterval,
} from '../hooks/useVideoDetail';
import type { BatchStageItem, VideoDetail } from '../types';

// batchPollInterval 은 배치 진행 중일 때만 폴링 간격(ms), 종료 시 false 를 반환한다.
// 실제 타이머 폴링은 TanStack Query 가 담당하므로 여기선 간격 결정 로직만 검증한다.

function makeDetail(
  stages: BatchStageItem[] | undefined,
  status: VideoDetail['status'] = 'PROCESSING',
): VideoDetail {
  return { stages, status } as unknown as VideoDetail;
}

function stage(name: string, status: BatchStageItem['status']): BatchStageItem {
  return { name, status, progress: null };
}

describe('batchPollInterval', () => {
  it('데이터가 없으면 폴링하지 않는다(false)', () => {
    expect(batchPollInterval(undefined)).toBe(false);
  });

  it('stages 가 빈배열(배치 로그 없는 기존 영상)이면 폴링하지 않는다(false)', () => {
    expect(batchPollInterval(makeDetail([]))).toBe(false);
  });

  it('stages 가 undefined 이면 폴링하지 않는다(false)', () => {
    expect(batchPollInterval(makeDetail(undefined))).toBe(false);
  });

  it('진행 중(PROGRESS) 단계가 있으면 폴링 간격을 반환한다', () => {
    const stages = [stage('DEIDENTIFY', 'DONE'), stage('MARKING', 'PROGRESS')];
    expect(batchPollInterval(makeDetail(stages))).toBe(BATCH_POLL_INTERVAL_MS);
  });

  it('대기(PENDING) 단계가 있으면 폴링 간격을 반환한다', () => {
    const stages = [stage('DEIDENTIFY', 'DONE'), stage('MARKING', 'PENDING')];
    expect(batchPollInterval(makeDetail(stages))).toBe(BATCH_POLL_INTERVAL_MS);
  });

  it('모든 단계가 DONE 이면 폴링을 중지한다(false)', () => {
    const stages = [stage('DEIDENTIFY', 'DONE'), stage('MARKING', 'DONE')];
    expect(batchPollInterval(makeDetail(stages))).toBe(false);
  });

  it('단계 중 FAIL 이 있으면 잔여 PENDING 이 있어도 폴링을 중지한다(false)', () => {
    const stages = [stage('DEIDENTIFY', 'FAIL'), stage('MARKING', 'PENDING')];
    expect(batchPollInterval(makeDetail(stages))).toBe(false);
  });

  it('영상이 COMPLETED 이면 진행 단계가 있어도 폴링을 중지한다(false)', () => {
    const stages = [stage('MARKING', 'PROGRESS')];
    expect(batchPollInterval(makeDetail(stages, 'COMPLETED'))).toBe(false);
  });

  it('영상이 APPROVED 이면 폴링을 중지한다(false)', () => {
    const stages = [stage('MARKING', 'PENDING')];
    expect(batchPollInterval(makeDetail(stages, 'APPROVED'))).toBe(false);
  });
});

// ★ 재기동은 접수이지 완료가 아니다. 접수 직후의 상세는 진행 로그가 아직 직전 실패 그대로라
//   위 FAIL 규칙이 즉시 폴링을 멈춘다 — 그러면 사용자에게는 눌렀는데 아무 일도 없는 것과
//   구분되지 않는다. 판정 축은 **영상 상태**(PROCESSING)이고, 창은 그 추적의 **상한**일 뿐이다.
describe('batchPollInterval — 「처리 중」 추적과 그 상한', () => {
  const NOW = 1_760_000_000_000;
  const openWindow = { pollUntil: NOW + BATCH_PROCESSING_POLL_WINDOW_MS, now: NOW };
  const closedWindow = { pollUntil: NOW - 1, now: NOW };

  it('처리 중이고 창이 열려 있으면 FAIL 이어도 폴링한다', () => {
    const stages = [stage('DEIDENTIFY', 'DONE'), stage('VLM', 'FAIL')];
    expect(batchPollInterval(makeDetail(stages), openWindow)).toBe(BATCH_POLL_INTERVAL_MS);
  });

  it('처리 중이고 창이 열려 있으면 stages 가 빈배열(단계 미상 실패)이어도 폴링한다', () => {
    expect(batchPollInterval(makeDetail([]), openWindow)).toBe(BATCH_POLL_INTERVAL_MS);
  });

  it('처리 중이고 창이 열려 있으면 전부 DONE 이어도 폴링한다', () => {
    // 재기동 직후 직전 실행의 로그가 전부 DONE 인 경우도 같은 구간이다.
    const stages = [stage('DEIDENTIFY', 'DONE'), stage('MARKING', 'DONE')];
    expect(batchPollInterval(makeDetail(stages), openWindow)).toBe(BATCH_POLL_INTERVAL_MS);
  });

  // ★★ 판정 축이 시간이 아니라 상태임을 고정하는 가드 — 이 단언이 없으면 창만으로 폴링이 열려
  //    "실패로 끝난 영상"까지 창 동안 따라가게 된다(구 동작).
  it('처리 중이 아니면(FAILED) 창이 열려 있어도 폴링하지 않는다', () => {
    const stages = [stage('VLM', 'FAIL')];
    expect(batchPollInterval(makeDetail(stages, 'FAILED'), openWindow)).toBe(false);
  });

  it('처리 중이 아니면(MARKING_READY) 창이 열려 있어도 폴링하지 않는다', () => {
    expect(batchPollInterval(makeDetail([], 'MARKING_READY'), openWindow)).toBe(false);
  });

  it('창이 닫히면 처리 중이어도 멈춘다 — 고착 영상에서 무한 폴링(self-DoS)을 만들지 않는다', () => {
    const stages = [stage('VLM', 'FAIL')];
    expect(batchPollInterval(makeDetail(stages), closedWindow)).toBe(false);
  });

  it('창 안에서 배치가 실제로 시작되면 기존 규칙이 이어받는다', () => {
    const stages = [stage('DEIDENTIFY', 'DONE'), stage('VLM', 'PROGRESS')];
    // 창이 이미 닫혔어도 진행 중이므로 계속 따라간다.
    expect(batchPollInterval(makeDetail(stages), closedWindow)).toBe(BATCH_POLL_INTERVAL_MS);
  });

  it('영상이 이미 종결(COMPLETED)이면 창이 열려 있어도 폴링하지 않는다', () => {
    const stages = [stage('VLM', 'FAIL')];
    expect(batchPollInterval(makeDetail(stages, 'COMPLETED'), openWindow)).toBe(false);
  });

  it('창을 지정하지 않으면 처리 중이어도 폴링하지 않는다', () => {
    const stages = [stage('VLM', 'FAIL')];
    expect(batchPollInterval(makeDetail(stages), { now: NOW })).toBe(false);
  });
});

// [@design API-167] [@design SCREEN-009] 선두 비식별 재시도 — 배치 상태·진행 로그가 움직이지 않으므로
// 「진행 중」은 비식별 이력 최신 회차로, 「접수 직후」는 짧은 상한 창과 이력 기준선으로 판정한다.
describe('batchPollInterval — 선두 비식별 재시도', () => {
  const NOW = 1_000_000;

  function leadDetail(
    history: { procLogSn: number; procSttsCd: string }[],
    overrides: Record<string, unknown> = {},
  ): VideoDetail {
    return {
      status: 'PENDING',
      deIdntfYn: 'F',
      stages: [],
      deidentHistory: history,
      ...overrides,
    } as unknown as VideoDetail;
  }

  const failedOnce = [{ procLogSn: 1, procSttsCd: 'FAILED' }];
  const acceptWatch = { until: NOW + LEAD_DEIDENT_ACCEPT_POLL_WINDOW_MS, baselineProcLogSn: 1 };

  it('대조군_접수_추적이_없는_멈춘_실패는_폴링하지_않는다', () => {
    expect(batchPollInterval(leadDetail(failedOnce), { now: NOW })).toBe(false);
  });

  it('접수_직후_새_회차가_아직_없으면_따라간다', () => {
    expect(
      batchPollInterval(leadDetail(failedOnce), { now: NOW, leadDeidentAccept: acceptWatch }),
    ).toBe(BATCH_POLL_INTERVAL_MS);
  });

  it('이력이_하나도_없던_영상도_접수_직후면_따라간다', () => {
    expect(
      batchPollInterval(leadDetail([]), {
        now: NOW,
        leadDeidentAccept: { ...acceptWatch, baselineProcLogSn: null },
      }),
    ).toBe(BATCH_POLL_INTERVAL_MS);
  });

  it('접수_추적_창이_지나면_새_회차가_없어도_멈춘다_무한_폴링_없음', () => {
    expect(
      batchPollInterval(leadDetail(failedOnce), {
        now: acceptWatch.until,
        leadDeidentAccept: acceptWatch,
      }),
    ).toBe(false);
  });

  it('새_회차가_진행_중이면_상한_창_안에서_따라간다', () => {
    const running = leadDetail([{ procLogSn: 2, procSttsCd: 'REQUESTED' }, ...failedOnce]);
    expect(
      batchPollInterval(running, { now: NOW, pollUntil: NOW + BATCH_PROCESSING_POLL_WINDOW_MS }),
    ).toBe(BATCH_POLL_INTERVAL_MS);
    // 상한이 지나면 진행 중이 고착돼도 멈춘다.
    expect(batchPollInterval(running, { now: NOW, pollUntil: NOW })).toBe(false);
    expect(batchPollInterval(running, { now: NOW, pollUntil: null })).toBe(false);
  });

  it('새_회차가_실패로_종결되면_접수_창_안이어도_멈춘다', () => {
    const failedAgain = leadDetail([{ procLogSn: 2, procSttsCd: 'FAILED' }, ...failedOnce]);
    expect(
      batchPollInterval(failedAgain, { now: NOW, leadDeidentAccept: acceptWatch }),
    ).toBe(false);
  });

  it('비식별이_성공해_마킹_대기가_되면_멈춘다', () => {
    const succeeded = leadDetail([{ procLogSn: 2, procSttsCd: 'SUCCEEDED' }, ...failedOnce], {
      status: 'MARKING_READY',
      deIdntfYn: 'Y',
    });
    expect(
      batchPollInterval(succeeded, {
        now: NOW,
        leadDeidentAccept: acceptWatch,
        pollUntil: NOW + BATCH_PROCESSING_POLL_WINDOW_MS,
      }),
    ).toBe(false);
  });
});
