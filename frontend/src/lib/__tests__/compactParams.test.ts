import { describe, expect, it } from 'vitest';

import { compactParams } from '../compactParams';

describe('compactParams — 빈 값 제거 / 0·false 보존', () => {
  it('undefined_null_빈문자열_공백문자열_빈배열은_키째로_제거된다', () => {
    // given: axios 는 undefined 만 생략하고 ''/null 은 그대로 보낸다 (BE `status=` → 400)
    const params = {
      a: undefined,
      b: null,
      c: '',
      d: '   ',
      e: [] as string[],
      keep: 'x',
    };

    // when
    const out = compactParams(params);

    // then
    expect(Object.keys(out)).toEqual(['keep']);
  });

  it('숫자_0_과_false_는_보존된다', () => {
    // given: page=0 이 사라지면 첫 페이지 요청이 BE 기본값에 의존하게 된다.
    const out = compactParams({ page: 0, size: 20, flag: false });

    // then
    expect(out).toEqual({ page: 0, size: 20, flag: false });
    expect('page' in out).toBe(true);
    expect('flag' in out).toBe(true);
  });

  it('원본_객체를_변경하지_않는다', () => {
    // given
    const source = { q: '', page: 0 };

    // when
    const out = compactParams(source);

    // then: 불변성 (호출부가 같은 객체를 재사용할 수 있어야 한다)
    expect(source).toEqual({ q: '', page: 0 });
    expect(out).not.toBe(source);
  });

  it('값이_있는_배열은_유지된다', () => {
    expect(compactParams({ sort: ['regDt,desc'] })).toEqual({
      sort: ['regDt,desc'],
    });
  });
});
