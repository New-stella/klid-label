import { describe, expect, it } from 'vitest';

import {
  AUGMENT_CONDITION_PRESET_IDS,
  AUGMENT_CONDITION_PRESET_VALUES,
  AugmentType,
  LEGACY_AUGMENT_TYPES,
  PROCESS_KINDS,
  createMtdtPresetFor,
  isAugmentKind,
} from '../types';

/**
 * isAugmentKind 타입가드 — positive(값 집합 포함) 검사 검증.
 *
 * 부정 조건(RESOLUTION 만 제외)이 아니라 AugmentType 값 집합 기반으로 판정해야
 * PROCESS_KINDS 확장 시(예: 새 비-증강 종류 추가) 오분기되지 않는다.
 */
describe('isAugmentKind', () => {
  it('증강_AI는_true를_반환한다', () => {
    // given / when / then
    expect(isAugmentKind('AUGMENT')).toBe(true);
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

/**
 * 요청 축과 표시 축은 다른 값 집합이다 (ADR-059).
 *
 * BE 가 기존 파생본의 `AUG_TYPE_CD`(WINTER/NIGHT/RAIN)를 **백필하지 않았으므로**, 요청 입구는
 * 단일값으로 좁히되 조회·표시 경로는 구 값을 계속 만난다. 한 타입으로 합치면 둘 중 하나가
 * 조용히 깨진다.
 */
describe('증강 종류 — 요청 축과 표시 축', () => {
  it('요청_축은_AUGMENT_단일값이다', () => {
    expect(Object.values(AugmentType)).toEqual(['AUGMENT']);
  });

  it('처리_종류_카드는_증강AI와_해상도변경_두_장이다', () => {
    expect(PROCESS_KINDS).toEqual(['AUGMENT', 'RESOLUTION']);
  });

  it('구_3종은_요청_축이_아니라_표시_축에만_남는다', () => {
    // 그랜드퍼더링 — 지우면 기존 이력·결과 화면의 배지가 뭉개진다.
    expect(LEGACY_AUGMENT_TYPES).toEqual(['WINTER', 'NIGHT', 'RAIN']);
    // 요청 축에는 없다(되살리면 화면이 다시 구 값을 보낼 수 있게 된다).
    for (const legacy of LEGACY_AUGMENT_TYPES) {
      expect(Object.values(AugmentType) as string[]).not.toContain(legacy);
    }
  });
});

/**
 * 생성 조건 프리셋 — 겨울·야간·우천. **증강 종류에 관여하지 않는다**(ADR-059).
 */
describe('생성 조건 프리셋', () => {
  it('프리셋은_겨울_야간_우천_세_개다', () => {
    expect(AUGMENT_CONDITION_PRESET_IDS).toEqual(['WINTER', 'NIGHT', 'RAIN']);
  });

  it('겨울은_계절과_날씨_야간은_시간대_우천은_날씨를_채운다', () => {
    expect(AUGMENT_CONDITION_PRESET_VALUES.WINTER).toEqual({
      season: 'WINTER',
      weather: 'SNOW',
    });
    expect(AUGMENT_CONDITION_PRESET_VALUES.NIGHT).toEqual({ time: 'NIGHT' });
    expect(AUGMENT_CONDITION_PRESET_VALUES.RAIN).toEqual({ weather: 'RAIN' });
  });

  it('프리셋_초안은_채우지_않는_축을_빈_값으로_명시한다', () => {
    // 부분 병합이면 이전 프리셋 잔재가 남아 "야간인데 계절=겨울" 이 전송된다.
    expect(createMtdtPresetFor('NIGHT')).toEqual({
      time: 'NIGHT',
      season: '',
      weather: '',
      terrain: '',
      severity: '',
    });
  });

  it('프리셋_해제는_전부_빈_값이다', () => {
    expect(createMtdtPresetFor(null)).toEqual({
      time: '',
      season: '',
      weather: '',
      terrain: '',
      severity: '',
    });
  });

  it('프리셋_초안은_매_호출_새_객체다', () => {
    // 상수를 공유하면 한 화면의 편집이 다른 화면으로 샌다(불변성).
    expect(createMtdtPresetFor('WINTER')).not.toBe(createMtdtPresetFor('WINTER'));
  });
});
