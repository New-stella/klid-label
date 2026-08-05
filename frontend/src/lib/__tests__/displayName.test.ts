import { describe, expect, it } from 'vitest';

import { resolveDisplayName } from '../displayName';

describe('resolveDisplayName', () => {
  it('이름이_있으면_이름을_쓴다', () => {
    // given/when/then: 원값(사용자 번호)이 있어도 이름이 이긴다
    expect(resolveDisplayName('홍길동', '1024')).toBe('홍길동');
    expect(resolveDisplayName('김작업', 100)).toBe('김작업');
  });

  it('이름이_null이거나_undefined면_원값으로_폴백한다', () => {
    expect(resolveDisplayName(null, 'reviewer1')).toBe('reviewer1');
    expect(resolveDisplayName(undefined, 'reviewer1')).toBe('reviewer1');
    // 숫자 원값(reporterNo)도 문자열로 표시한다
    expect(resolveDisplayName(null, 100)).toBe('100');
  });

  it('이름이_빈문자열이거나_공백뿐이면_원값으로_폴백한다', () => {
    // null 만 확인하면 화면에 빈칸이 남아 폴백이 무력화된다
    expect(resolveDisplayName('', 'reviewer1')).toBe('reviewer1');
    expect(resolveDisplayName('   ', 'reviewer1')).toBe('reviewer1');
    expect(resolveDisplayName('\t\n ', 100)).toBe('100');
  });

  it('이름_앞뒤_공백은_다듬어_표시한다', () => {
    expect(resolveDisplayName('  홍길동  ', '1024')).toBe('홍길동');
  });

  it('둘_다_없으면_null_을_돌려_호출부가_기존_동작을_정하게_한다', () => {
    expect(resolveDisplayName(null, null)).toBeNull();
    expect(resolveDisplayName(undefined, undefined)).toBeNull();
    expect(resolveDisplayName('  ', '  ')).toBeNull();
    expect(resolveDisplayName('', null)).toBeNull();
  });

  it('원값이_숫자가_아닌_수치면_표시하지_않는다', () => {
    // NaN/Infinity 가 화면에 찍히지 않게 한다
    expect(resolveDisplayName(null, Number.NaN)).toBeNull();
    expect(resolveDisplayName(null, Number.POSITIVE_INFINITY)).toBeNull();
  });

  it('숫자_0_은_유효한_원값이라_표시한다', () => {
    // falsy 판정으로 0 을 떨어뜨리지 않는다
    expect(resolveDisplayName(null, 0)).toBe('0');
  });
});
