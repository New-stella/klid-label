import { describe, expect, it } from 'vitest';

import { AugmentType, PROCESS_KINDS, isAugmentKind } from '../types';

/**
 * isAugmentKind 타입가드 — positive(값 집합 포함) 검사 검증.
 *
 * 부정 조건(RESOLUTION 만 제외)이 아니라 AugmentType 값 집합 기반으로 판정해야
 * PROCESS_KINDS 확장 시(예: 새 비-증강 종류 추가) 오분기되지 않는다.
 */
describe('isAugmentKind', () => {
  it('증강_3종은_true를_반환한다', () => {
    // given / when / then
    expect(isAugmentKind('WINTER')).toBe(true);
    expect(isAugmentKind('NIGHT')).toBe(true);
    expect(isAugmentKind('RAIN')).toBe(true);
  });

  it('RESOLUTION은_false를_반환한다', () => {
    // given / when / then
    expect(isAugmentKind('RESOLUTION')).toBe(false);
  });

  it('AugmentType_값_집합에_포함된_종류만_증강으로_판정한다', () => {
    // given: PROCESS_KINDS 중 AugmentType 값 집합 여부와 일치해야 함
    const augmentValues = Object.values(AugmentType) as string[];
    // when / then
    for (const kind of PROCESS_KINDS) {
      expect(isAugmentKind(kind)).toBe(augmentValues.includes(kind));
    }
  });
});
