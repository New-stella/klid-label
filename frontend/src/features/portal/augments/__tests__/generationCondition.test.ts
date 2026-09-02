// 회귀 가드 — 생성 조건 표기. **항목 이름을 해석하지 않는다**는 것이 이 모듈의 존재 이유다.
//
// ★ 계약 셋이 모두 *"조건을 이루는 개별 항목과 그 허용 값역은 이 산출물에서 정하지 않는다"* 고
//   적고, 접수 창구는 *"내부 채널 증강 요청 창구의 조건 항목과 값역을 그대로 옮겨 오지 말 것"*
//   까지 못박는다. 그래서 한글 라벨 매핑을 두면 안 된다 — 두는 순간 그것이 계약의 사본이 되고,
//   서버가 항목을 넓히면 화면이 **모르는 항목을 조용히 감춘다**(값이 사라지는 쪽의 실패라
//   아무도 알아채지 못한다).
//
// ⚠ mutation 확인: 키를 한글로 바꾸는 매핑을 넣거나 모르는 키를 걸러 내게 만들면 아래
//   「원문 그대로」 케이스가 FAIL 해야 한다.
import { describe, expect, it } from 'vitest';

import {
  EMPTY_CONDITION_TEXT,
  formatGenerationCondition,
  summarizeGenerationCondition,
} from '../generationCondition';

describe('생성 조건 표기', () => {
  it('★서버가_준_항목_이름을_번역하지_않고_그대로_보인다', () => {
    const entries = formatGenerationCondition({ weather: 'RAIN', 처음보는항목: '값' });

    expect(entries).toEqual([
      { key: 'weather', value: 'RAIN' },
      { key: '처음보는항목', value: '값' },
    ]);
  });

  it('★모르는_항목을_걸러_내지_않는다_값이_사라지는_실패는_아무도_못_본다', () => {
    const entries = formatGenerationCondition({ zzzUnknown: 1, aaaUnknown: true });

    expect(entries.map((e) => e.key)).toEqual(['aaaUnknown', 'zzzUnknown']);
    expect(entries.map((e) => e.value)).toEqual(['true', '1']);
  });

  it('키_순서로_정렬한다_응답의_키_순서가_보장되지_않는다', () => {
    const a = summarizeGenerationCondition({ b: '2', a: '1' });
    const b = summarizeGenerationCondition({ a: '1', b: '2' });

    expect(a).toBe(b);
    expect(a).toBe('a: 1 · b: 2');
  });

  it('배열과_중첩_객체도_값을_잃지_않는다', () => {
    expect(formatGenerationCondition({ list: ['A', 'B'] })).toEqual([
      { key: 'list', value: 'A, B' },
    ]);
    expect(formatGenerationCondition({ nested: { k: 1 } })).toEqual([
      { key: 'nested', value: '{"k":1}' },
    ]);
  });

  it('조건이_비었거나_객체가_아니면_빈_배열이고_요약은_대시다', () => {
    expect(formatGenerationCondition({})).toEqual([]);
    expect(formatGenerationCondition(null)).toEqual([]);
    expect(formatGenerationCondition(undefined)).toEqual([]);
    expect(formatGenerationCondition(['배열은 조건이 아니다'])).toEqual([]);
    expect(summarizeGenerationCondition({})).toBe(EMPTY_CONDITION_TEXT);
  });
});
