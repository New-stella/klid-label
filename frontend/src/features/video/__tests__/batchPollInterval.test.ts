import { describe, expect, it } from 'vitest';

import {
  BATCH_POLL_INTERVAL_MS,
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
