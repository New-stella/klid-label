// 회귀 가드 — 생성 조건 표기. **두 축을 동시에** 지킨다.
//
// ① 아는 다섯 항목은 **정해진 차례로 우리말 이름**과 함께 보인다(시간대·계절·날씨·지형·심각도).
// ② **모르는 항목·모르는 값은 감추지 않는다** — 아는 다섯 뒤에 받은 그대로 이어 붙인다.
//
// ★ ②는 이번 라운드에서도 **그대로 유효하다.** 아는 다섯을 우리말로 옮기는 일과 모르는 항목을
//   거르는 일은 다른 축인데, ①을 더하면서 ②를 함께 걷어내기 쉽다. 걷어내면 서버가 항목을
//   넓히는 날 화면이 그 항목을 **조용히 감춘다**(값이 사라지는 쪽의 실패라 아무도 못 본다).
//
// ⚠ **구 근거 폐기**: *"조건을 이루는 개별 항목과 그 허용 값역은 정하지 않는다"* 와
//   *"내부 채널 증강 요청 창구의 조건 항목과 값역을 그대로 옮겨 오지 말 것"* 은 둘 다 폐기됐다.
//   그 문장을 근거로 우리말 이름을 다시 걷어내지 말 것.
//
// ⚠ mutation 확인: 우리말 이름을 지우면 ①이, 모르는 키를 걸러 내면 ②가 FAIL 해야 한다.
import { describe, expect, it } from 'vitest';

import {
  EMPTY_CONDITION_TEXT,
  formatGenerationCondition,
  summarizeGenerationCondition,
} from '../generationCondition';

describe('생성 조건 표기', () => {
  it('★아는_다섯_항목을_정해진_차례로_우리말_이름과_함께_보인다', () => {
    // 응답의 키 순서를 일부러 뒤집어 넣는다 — 표기 차례는 응답 순서가 아니라 사양이 정한다.
    const entries = formatGenerationCondition({
      severity: 'HIGH',
      weather: 'SNOW',
      time: 'NIGHT',
      terrain: 'ROAD',
      season: 'WINTER',
    });

    expect(entries).toEqual([
      { key: 'time', label: '시간대', value: '밤' },
      { key: 'season', label: '계절', value: '겨울' },
      { key: 'weather', label: '날씨', value: '눈' },
      { key: 'terrain', label: '지형', value: '도로' },
      { key: 'severity', label: '심각도', value: '높음' },
    ]);
  });

  it('★모르는_항목을_걸러_내지_않는다_아는_다섯_뒤에_받은_그대로_이어_붙인다', () => {
    const entries = formatGenerationCondition({
      처음보는항목: '값',
      weather: 'RAIN',
      zzzUnknown: 1,
      aaaUnknown: true,
    });

    expect(entries).toEqual([
      { key: 'weather', label: '날씨', value: '비' },
      { key: 'aaaUnknown', label: 'aaaUnknown', value: 'true' },
      { key: 'zzzUnknown', label: 'zzzUnknown', value: '1' },
      { key: '처음보는항목', label: '처음보는항목', value: '값' },
    ]);
  });

  it('★아는_항목의_모르는_코드도_받은_값을_그대로_보인다', () => {
    // 위탁받는 쪽이 값역을 넓히거나 옛 자유 입력 적재분이 섞여도 값이 사라지지 않아야 한다.
    expect(formatGenerationCondition({ weather: 'HAIL' })).toEqual([
      { key: 'weather', label: '날씨', value: 'HAIL' },
    ]);
  });

  it('모르는_항목끼리는_키_순서로_정렬한다_응답의_키_순서가_보장되지_않는다', () => {
    const a = summarizeGenerationCondition({ b: '2', a: '1' });
    const b = summarizeGenerationCondition({ a: '1', b: '2' });

    expect(a).toBe(b);
    expect(a).toBe('a: 1 · b: 2');
  });

  it('한_줄_요약은_우리말_이름을_쓴다', () => {
    expect(
      summarizeGenerationCondition({ time: 'DAWN', season: 'SPRING', 기타: 'X' }),
    ).toBe('시간대: 새벽 · 계절: 봄 · 기타: X');
  });

  it('배열과_중첩_객체도_값을_잃지_않는다', () => {
    expect(formatGenerationCondition({ list: ['A', 'B'] })).toEqual([
      { key: 'list', label: 'list', value: 'A, B' },
    ]);
    expect(formatGenerationCondition({ nested: { k: 1 } })).toEqual([
      { key: 'nested', label: 'nested', value: '{"k":1}' },
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
