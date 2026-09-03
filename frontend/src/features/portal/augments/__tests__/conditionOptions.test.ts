// 회귀 가드 — 포털 증강 요청의 **생성 조건 어휘**(항목·순서·허용 코드·우리말 표시명).
//
// ★ **형식만 보는 검사는 값이 서로 뒤바뀌어도 통과한다.** "다섯 항목이 있다"·"전부 한글이다"
//   같은 검사는 `DAWN: '밤'` 처럼 값이 뒤집혀도 초록이고, 가드가 있다는 사실이 오히려 안심을
//   만들어 값 오염을 덮는다. 그래서 **값 자체를 여기에 고정**한다 — 아래 표는 화면 사양이
//   못박은 대응이며, 이 파일이 그것을 두 번째로 적는 것이 바로 그 대조의 목적이다.
//
// ⚠ mutation 확인: 코드 하나를 지우거나(`DUSK` 제거) 표시명을 바꾸면(`해질녘`→`황혼`) 아래
//   케이스가 FAIL 해야 한다.
import { describe, expect, it } from 'vitest';

import { AUGMENT_MTDT_CODES } from '@/features/augment/types';

import {
  PORTAL_AUGMENT_CONDITION_CODES,
  PORTAL_AUGMENT_CONDITION_FIELDS,
  PORTAL_AUGMENT_CONDITION_KEYS,
  PORTAL_AUGMENT_PROMPT_MAX_LENGTH,
  createEmptyPortalAugmentCondition,
  isPortalAugmentConditionCode,
  missingPortalAugmentConditionLabels,
  portalAugmentConditionCodeLabel,
  portalAugmentConditionLabel,
  toPortalAugmentGenerationCondition,
} from '../conditionOptions';

/** 화면 사양이 못박은 표시명↔코드 대응 — 값 고정 축. */
const EXPECTED_CODE_LABELS: Record<string, Record<string, string>> = {
  time: { DAWN: '새벽', DAY: '낮', DUSK: '해질녘', NIGHT: '밤' },
  season: { SPRING: '봄', SUMMER: '여름', AUTUMN: '가을', WINTER: '겨울' },
  weather: { CLEAR: '맑음', CLOUDY: '흐림', RAIN: '비', SNOW: '눈', FOG: '안개', WINDY: '바람' },
  terrain: {
    ROAD: '도로',
    UNDERPASS: '지하차도',
    RIVER: '하천',
    URBAN: '도시',
    RESIDENTIAL: '주거지',
    RURAL: '농촌',
    MOUNTAIN: '산지',
    FOREST: '산림',
  },
  severity: { LOW: '낮음', MEDIUM: '보통', HIGH: '높음' },
};

const EXPECTED_FIELD_LABELS: Record<string, string> = {
  time: '시간대',
  season: '계절',
  weather: '날씨',
  terrain: '지형',
  severity: '심각도',
};

describe('포털 증강 생성 조건 어휘', () => {
  it('★항목은_다섯이고_차례가_고정이다', () => {
    expect([...PORTAL_AUGMENT_CONDITION_KEYS]).toEqual([
      'time',
      'season',
      'weather',
      'terrain',
      'severity',
    ]);
  });

  it('★허용_코드를_값으로_고정한다_접수_창구가_정본이다', () => {
    expect(
      Object.fromEntries(
        PORTAL_AUGMENT_CONDITION_KEYS.map((k) => [k, [...PORTAL_AUGMENT_CONDITION_CODES[k]]]),
      ),
    ).toEqual({
      time: ['DAWN', 'DAY', 'DUSK', 'NIGHT'],
      season: ['SPRING', 'SUMMER', 'AUTUMN', 'WINTER'],
      weather: ['CLEAR', 'CLOUDY', 'RAIN', 'SNOW', 'FOG', 'WINDY'],
      terrain: ['ROAD', 'UNDERPASS', 'RIVER', 'URBAN', 'RESIDENTIAL', 'RURAL', 'MOUNTAIN', 'FOREST'],
      severity: ['LOW', 'MEDIUM', 'HIGH'],
    });
  });

  it('★표시명을_값으로_고정한다_형식만_보면_뒤바뀐_값이_통과한다', () => {
    for (const key of PORTAL_AUGMENT_CONDITION_KEYS) {
      expect(PORTAL_AUGMENT_CONDITION_FIELDS[key].label).toBe(EXPECTED_FIELD_LABELS[key]);
      expect({ ...PORTAL_AUGMENT_CONDITION_FIELDS[key].codeLabel }).toEqual(
        EXPECTED_CODE_LABELS[key],
      );
    }
  });

  it('★모든_허용_코드에_표시명이_있다_빠진_코드는_드롭다운에_빈칸으로_뜬다', () => {
    for (const key of PORTAL_AUGMENT_CONDITION_KEYS) {
      const labels = PORTAL_AUGMENT_CONDITION_FIELDS[key].codeLabel as Record<string, string>;
      for (const code of PORTAL_AUGMENT_CONDITION_CODES[key] as readonly string[]) {
        expect(labels[code], `${key}.${code}`).toBeTruthy();
      }
    }
  });

  it('★코드_값역은_내부_채널과_같아야_한다_표시명은_각_화면_사양이_소유해_다를_수_있다', () => {
    // 두 채널의 생성 조건은 같은 항목·같은 값역을 쓴다는 것이 확정이다. 한쪽만 넓어지면
    // 정상 값을 다른 쪽이 먼저 막으므로 여기서 맞대어 본다(시험에서만 맞댄다 — 표시명이 달라
    // 상수를 공유하지는 않는다).
    for (const key of PORTAL_AUGMENT_CONDITION_KEYS) {
      expect([...PORTAL_AUGMENT_CONDITION_CODES[key]], key).toEqual([
        ...(AUGMENT_MTDT_CODES[key] as readonly string[]),
      ]);
    }
    // 표시명은 실제로 갈린다 — 같아야 한다고 오해해 한쪽을 다른 쪽에 맞추면 상대 화면 문구가
    // 조용히 바뀐다.
    expect(PORTAL_AUGMENT_CONDITION_FIELDS.time.codeLabel.DUSK).not.toBe('황혼');
  });

  it('★이벤트_유형과_증강_종류는_이_어휘에_없다_요청자가_고르지_않는다', () => {
    const keys = new Set<string>(PORTAL_AUGMENT_CONDITION_KEYS);
    for (const forbidden of ['eventType', 'eventSubType', 'augTypes', 'types', 'augTypeCd']) {
      expect(keys.has(forbidden), forbidden).toBe(false);
      expect(Object.keys(PORTAL_AUGMENT_CONDITION_FIELDS)).not.toContain(forbidden);
    }
  });

  it('자유_지시문_상한은_1000자다', () => {
    expect(PORTAL_AUGMENT_PROMPT_MAX_LENGTH).toBe(1000);
  });

  it('허용_코드_판정은_fail_closed_다', () => {
    expect(isPortalAugmentConditionCode('time', 'NIGHT')).toBe(true);
    expect(isPortalAugmentConditionCode('time', 'night')).toBe(false);
    expect(isPortalAugmentConditionCode('time', '')).toBe(false);
    // 코드 공간이 축을 넘나들지 않는다 — 계절 값이 시간대 자리에 통과하면 안 된다.
    expect(isPortalAugmentConditionCode('time', 'WINTER')).toBe(false);
  });

  it('★다섯이_다_차야만_전송값이_나온다_하나라도_비면_null_이다', () => {
    const draft = createEmptyPortalAugmentCondition();
    expect(toPortalAugmentGenerationCondition(draft)).toBeNull();

    draft.time = 'NIGHT';
    draft.season = 'WINTER';
    draft.weather = 'SNOW';
    draft.terrain = 'ROAD';
    expect(toPortalAugmentGenerationCondition(draft)).toBeNull();
    expect(missingPortalAugmentConditionLabels(draft)).toEqual(['심각도']);

    draft.severity = 'HIGH';
    expect(toPortalAugmentGenerationCondition(draft)).toEqual({
      time: 'NIGHT',
      season: 'WINTER',
      weather: 'SNOW',
      terrain: 'ROAD',
      severity: 'HIGH',
    });
    expect(missingPortalAugmentConditionLabels(draft)).toEqual([]);
  });

  it('코드_밖_값은_다_찼어도_전송값이_되지_않는다', () => {
    const draft = {
      time: 'NIGHT',
      season: 'WINTER',
      weather: 'SNOW',
      terrain: 'ROAD',
      severity: '아주높음',
    };
    expect(toPortalAugmentGenerationCondition(draft)).toBeNull();
    expect(missingPortalAugmentConditionLabels(draft)).toEqual(['심각도']);
  });

  it('★모르는_항목과_모르는_값은_받은_그대로_돌려준다_감추지_않는다', () => {
    expect(portalAugmentConditionLabel('처음보는항목')).toBe('처음보는항목');
    expect(portalAugmentConditionCodeLabel('처음보는항목', 'X')).toBe('X');
    expect(portalAugmentConditionCodeLabel('weather', 'HAIL')).toBe('HAIL');
    expect(portalAugmentConditionCodeLabel('weather', 'SNOW')).toBe('눈');
  });
});
