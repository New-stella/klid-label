import { describe, expect, it } from 'vitest';

import { canMark, isMarkingDone } from '../markingEligibility';

describe('markingEligibility.canMark', () => {
  it('MARKING_READY_비식별완료_deIdntfYn_Y_이면_true', () => {
    // given: 마킹 준비 완료 + 비식별 완료(deIdntfYn='Y')
    // when / then
    expect(canMark({ status: 'MARKING_READY', deIdntfYn: 'Y' })).toBe(true);
  });

  it('MARKING_READY_비식별완료_deidentStatus_DONE_이면_true', () => {
    expect(canMark({ status: 'MARKING_READY', deidentStatus: 'DONE' })).toBe(true);
  });

  it('MARKING_READY_비식별정보_없으면_true_fail_open_for_UX', () => {
    // 비식별 신호 없음(undefined) → FE 는 선차단하지 않는다(BE 가드가 백스톱).
    expect(canMark({ status: 'MARKING_READY' })).toBe(true);
  });

  it('MARKING_READY_비식별진행중_IN_PROGRESS_이면_false', () => {
    expect(
      canMark({ status: 'MARKING_READY', deidentStatus: 'IN_PROGRESS' }),
    ).toBe(false);
  });

  it('MARKING_READY_비식별실패_deidentStatus_FAILED_이면_false', () => {
    expect(canMark({ status: 'MARKING_READY', deidentStatus: 'FAILED' })).toBe(false);
  });

  it('MARKING_READY_비식별미처리_deIdntfYn_N_이면_false', () => {
    expect(canMark({ status: 'MARKING_READY', deIdntfYn: 'N' })).toBe(false);
  });

  it('MARKING_READY_비식별실패_deIdntfYn_F_이면_false', () => {
    expect(canMark({ status: 'MARKING_READY', deIdntfYn: 'F' })).toBe(false);
  });

  it('모순신호_deIdntfYn_Y_이지만_deidentStatus_FAILED_이면_deidentStatus_우선으로_false', () => {
    // isMarkingBlocked 는 deidentStatus(IN_PROGRESS/FAILED)를 먼저 판정하므로
    // deIdntfYn='Y'(완료 신호)라도 deidentStatus='FAILED'면 마킹 진입을 차단한다.
    expect(
      canMark({ status: 'MARKING_READY', deIdntfYn: 'Y', deidentStatus: 'FAILED' }),
    ).toBe(false);
  });

  it('status_가_MARKING_READY_가_아니면_비식별완료라도_false', () => {
    // 경계: 배치 단계가 준비완료가 아니면 canMark 는 무조건 false
    expect(canMark({ status: 'PROCESSING', deIdntfYn: 'Y' })).toBe(false);
    expect(canMark({ status: 'COMPLETED', deIdntfYn: 'Y' })).toBe(false);
    expect(canMark({ status: 'FAILED', deIdntfYn: 'Y' })).toBe(false);
    expect(canMark({ status: 'PENDING', deIdntfYn: 'Y' })).toBe(false);
    expect(canMark({ deIdntfYn: 'Y' })).toBe(false); // status undefined
  });
});

describe('markingEligibility.isMarkingDone', () => {
  it('PROCESSING_COMPLETED_FAILED_는_true', () => {
    expect(isMarkingDone({ status: 'PROCESSING' })).toBe(true);
    expect(isMarkingDone({ status: 'COMPLETED' })).toBe(true);
    expect(isMarkingDone({ status: 'FAILED' })).toBe(true);
  });

  it('MARKING_READY_PENDING_undefined_는_false', () => {
    expect(isMarkingDone({ status: 'MARKING_READY' })).toBe(false);
    expect(isMarkingDone({ status: 'PENDING' })).toBe(false);
    expect(isMarkingDone({})).toBe(false);
  });
});
