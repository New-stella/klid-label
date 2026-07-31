import { describe, expect, it } from 'vitest';

import { normalizeVisibleText } from '../visibleText';

/**
 * BE `VisibleTextNormalizer` 와 **같은 규칙**인지 검증한다.
 *
 * 기준(백엔드 주석 그대로):
 * - 제거: ISO 제어문자(NUL·개행·탭), Cf(FORMAT: ZWSP·BOM·WJ·RLO), Zl(U+2028), Zp(U+2029)
 * - 일반 공백으로 치환: Zs(NBSP·U+3000 …)
 * - 그 뒤 trim, 남는 게 없으면 null
 *
 * FE 가 이 규칙을 어긋나게 구현하면 "FE 는 통과시켰는데 BE 가 400" 이 되어
 * 사용자가 400 을 보게 된다(수용 기준 위반).
 *
 * 보이지 않는 문자는 소스에 리터럴로 넣지 않고 코드포인트로 만든다 —
 * 리터럴은 리뷰·편집기에서 식별되지 않아 테스트 자체가 조용히 변질된다.
 */
const cp = (codePoint: number) => String.fromCodePoint(codePoint);

const NBSP = cp(0x00a0); // Zs — 일반 공백으로 치환 대상
const IDEOGRAPHIC_SPACE = cp(0x3000); // Zs
const ZWSP = cp(0x200b); // Cf — 제거 대상
const BOM = cp(0xfeff); // Cf
const WORD_JOINER = cp(0x2060); // Cf
const RLO = cp(0x202e); // Cf
const LINE_SEP = cp(0x2028); // Zl
const PARA_SEP = cp(0x2029); // Zp
const NUL = cp(0x0000); // ISO control

describe('normalizeVisibleText (BE VisibleTextNormalizer 미러)', () => {
  it('일반_문자열은_앞뒤_공백만_제거된다', () => {
    expect(normalizeVisibleText('  NIGHT  ')).toBe('NIGHT');
    expect(normalizeVisibleText('강한 폭우')).toBe('강한 폭우');
  });

  it('null_undefined_는_null', () => {
    expect(normalizeVisibleText(null)).toBeNull();
    expect(normalizeVisibleText(undefined)).toBeNull();
  });

  it('공백만_입력하면_null', () => {
    expect(normalizeVisibleText('   ')).toBeNull();
    expect(normalizeVisibleText('')).toBeNull();
  });

  it('보이지_않는_문자만_있으면_null', () => {
    expect(normalizeVisibleText(NBSP)).toBeNull();
    expect(normalizeVisibleText(ZWSP)).toBeNull();
    expect(normalizeVisibleText(BOM)).toBeNull();
    expect(normalizeVisibleText(WORD_JOINER)).toBeNull();
    expect(normalizeVisibleText(IDEOGRAPHIC_SPACE)).toBeNull();
    expect(normalizeVisibleText(ZWSP + NBSP + BOM)).toBeNull();
  });

  it('제어문자와_행문단구분자는_제거된다', () => {
    expect(normalizeVisibleText('NI' + NUL + 'GHT')).toBe('NIGHT');
    expect(normalizeVisibleText('NI\nGHT')).toBe('NIGHT');
    expect(normalizeVisibleText('NI\tGHT')).toBe('NIGHT');
    expect(normalizeVisibleText('NI' + LINE_SEP + 'GHT')).toBe('NIGHT');
    expect(normalizeVisibleText('NI' + PARA_SEP + 'GHT')).toBe('NIGHT');
    expect(normalizeVisibleText('NI' + RLO + 'GHT')).toBe('NIGHT');
  });

  it('NBSP는_제거가_아니라_일반공백으로_치환된다', () => {
    // 단어 사이 NBSP 는 구분 의미를 가지므로 지우지 않는다 (BE 규칙과 동일).
    expect(normalizeVisibleText('폭우' + NBSP + '경보')).toBe('폭우 경보');
    expect(normalizeVisibleText('폭우' + IDEOGRAPHIC_SPACE + '경보')).toBe('폭우 경보');
  });

  it('보조평면_문자가_쪼개지지_않는다', () => {
    expect(normalizeVisibleText(' 🌧 ')).toBe('🌧');
  });
});
