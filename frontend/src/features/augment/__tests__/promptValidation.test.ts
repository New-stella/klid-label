import { describe, expect, it } from 'vitest';

import {
  augmentConditionBlockReasons,
  validateAugmentConditions,
  type AugmentConditionDraft,
} from '@/features/augment/promptValidation';
import {
  AUGMENT_PROMPT_MAX_LENGTH,
  createEmptyAugmentMtdt,
} from '@/features/augment/types';

/** 보이지 않는 문자는 코드포인트로 만든다(소스 리터럴 금지 — 리뷰에서 식별 불가). */
const cp = (codePoint: number) => String.fromCodePoint(codePoint);
const ZWSP = cp(0x200b);
const NBSP = cp(0x00a0);
const BOM = cp(0xfeff);

/**
 * 증강 요청 생성 조건 축의 **fail-closed 2차 방어** 회귀 가드 (연동명세서 v1.3).
 *
 * ⚠ **왜 화면 테스트로 대체할 수 없나**: 화면은 이벤트 유형이 침수가 아니게 되는 순간 세부
 * 유형을 상태에서 비운다. 그래서 화면 경로만 검증하면 이 함수의 "산불이면 세부 유형을 싣지
 * 않는다" 판정이 **한 번도 실행되지 않고**, 그 판정을 지워도 화면 테스트는 전부 통과한다
 * (실제로 변이 실험에서 그렇게 통과했다). 두 겹이 각각 자기 가드를 가져야 성립한다.
 */
describe('validateAugmentConditions — 전송값 좁히기 (연동명세서 v1.3)', () => {
  const validMtdt = {
    time: 'NIGHT',
    season: 'WINTER',
    weather: 'RAIN',
    terrain: 'ROAD',
    severity: 'HIGH',
  };

  const draft = (over: Partial<AugmentConditionDraft> = {}): AugmentConditionDraft => ({
    evntType: 'FLOOD',
    evntSubtype: '',
    mtdt: { ...validMtdt },
    prompt: '',
    ...over,
  });

  it('침수가_아니면_세부유형을_받았어도_싣지_않는다', () => {
    // given: 화면이 비우지 못한(또는 우회한) 잔재가 초안에 남아 있는 상태
    const result = validateAugmentConditions(
      draft({ evntType: 'WILDFIRE', evntSubtype: 'ROAD_FLOOD' }),
    );

    // then: 계약에 산불 세부 코드가 없어 함께 보내면 BE 400 이다 — 조용히 제외한다.
    expect(result.ok).toBe(true);
    expect(result.value).not.toHaveProperty('evntSubtype');
  });

  it('침수면_세부유형을_그대로_싣는다', () => {
    const result = validateAugmentConditions(
      draft({ evntType: 'FLOOD', evntSubtype: 'UNDERPASS_FLOOD' }),
    );

    expect(result.value?.evntSubtype).toBe('UNDERPASS_FLOOD');
  });

  it('허용_코드_밖의_세부유형은_싣지_않는다', () => {
    const result = validateAugmentConditions(
      draft({ evntSubtype: 'LANDSLIDE' }),
    );

    expect(result.ok).toBe(true);
    expect(result.value).not.toHaveProperty('evntSubtype');
  });

  it('이벤트_유형이_없거나_허용_코드_밖이면_유효하지_않다', () => {
    expect(validateAugmentConditions(draft({ evntType: '' })).ok).toBe(false);
    expect(validateAugmentConditions(draft({ evntType: 'EARTHQUAKE' })).ok).toBe(false);
    expect(validateAugmentConditions(draft({ evntType: '' })).value).toBeNull();
  });

  it('생성_조건은_다섯_항목_전부_있어야_한다', () => {
    // 벤더 계약은 "최소 1개" 지만 **우리 규칙은 전부 필수**다(더 엄격한 쪽이 의도된 선택).
    // 하나라도 비면 벤더가 어떤 기본값으로 채울지 알 수 없어 결과가 비결정적이 된다.
    const one = validateAugmentConditions(
      draft({ mtdt: { ...createEmptyAugmentMtdt(), time: 'NIGHT' } }),
    );

    expect(one.ok).toBe(false);
    expect(one.value).toBeNull();
    expect(Object.keys(one.errors.mtdt).sort()).toEqual(
      ['season', 'severity', 'terrain', 'weather'].sort(),
    );
  });

  it('허용_코드_밖의_생성_조건_값은_거부한다', () => {
    // 화면은 드롭다운이라 만들어질 수 없는 값이지만, 배선 실수를 조용히 통과시키지 않는다.
    const result = validateAugmentConditions(
      draft({ mtdt: { ...validMtdt, terrain: 'ALLEY' } }),
    );

    expect(result.ok).toBe(false);
    expect(result.errors.mtdt.terrain).toBeDefined();
  });

  it('자유_지시문은_선택이라_비면_키를_싣지_않는다', () => {
    expect(validateAugmentConditions(draft({ prompt: '' })).value).not.toHaveProperty(
      'prompt',
    );
    // 보이지 않는 문자만 채운 값도 "입력되지 않음" 이다(BE VisibleTextNormalizer 미러).
    expect(
      validateAugmentConditions(draft({ prompt: ZWSP + NBSP + BOM })).value,
    ).not.toHaveProperty('prompt');
  });

  it('자유_지시문은_정규화한_값으로_싣는다', () => {
    const result = validateAugmentConditions(draft({ prompt: '  도로 구조 유지  ' }));

    expect(result.value?.prompt).toBe('도로 구조 유지');
  });

  it('자유_지시문_상한을_넘으면_유효하지_않다', () => {
    const over = validateAugmentConditions(
      draft({ prompt: 'A'.repeat(AUGMENT_PROMPT_MAX_LENGTH + 1) }),
    );
    const exact = validateAugmentConditions(
      draft({ prompt: 'A'.repeat(AUGMENT_PROMPT_MAX_LENGTH) }),
    );

    expect(over.ok).toBe(false);
    expect(over.errors.prompt).toBeDefined();
    expect(exact.ok).toBe(true);
  });

  it('제출_불가_사유는_모자란_축을_모두_알려준다', () => {
    const reasons = augmentConditionBlockReasons(
      validateAugmentConditions(
        draft({
          evntType: '',
          mtdt: { ...validMtdt, severity: '' },
          prompt: 'A'.repeat(AUGMENT_PROMPT_MAX_LENGTH + 1),
        }),
      ),
    );

    expect(reasons).toEqual([
      '이벤트 유형',
      '생성 조건(심각도)',
      '자유 지시문 길이',
    ]);
  });

  it('전부_유효하면_전송값이_계약_형태로_나온다', () => {
    const result = validateAugmentConditions(
      draft({ evntSubtype: 'RIVER_OVERFLOW', prompt: '유지해줘' }),
    );

    expect(result.ok).toBe(true);
    expect(result.value).toEqual({
      evntType: 'FLOOD',
      evntSubtype: 'RIVER_OVERFLOW',
      mtdt: validMtdt,
      prompt: '유지해줘',
    });
  });
});
