import { readFileSync } from 'node:fs';
import path from 'node:path';

import resolveConfig from 'tailwindcss/resolveConfig';
import { describe, expect, it } from 'vitest';

// tailwind.config.js 는 타입 선언이 없는 plain JS(ESM default export)
// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig from '../../tailwind.config.js';

const fullConfig = resolveConfig(tailwindConfig as never);
const colors = fullConfig.theme.colors as Record<string, unknown>;
const fontFamily = fullConfig.theme.fontFamily as Record<string, string[]>;
const fontSize = fullConfig.theme.fontSize as Record<string, unknown>;
const borderRadius = fullConfig.theme.borderRadius as Record<string, string>;
const boxShadow = fullConfig.theme.boxShadow as Record<string, string>;

const asObj = (v: unknown): Record<string, unknown> => v as Record<string, unknown>;
const repoRoot = path.resolve(__dirname, '../..');

describe('KRDS 디자인 토큰 — 색상', () => {
  it('primary가_KRDS_네이비_0F4C97로_교체된다', () => {
    // given: KRDS primary 팔레트
    const primary = asObj(colors.primary);
    // then
    expect(primary.DEFAULT).toBe('#0F4C97');
    expect(primary['700']).toBe('#0F4C97');
    expect(primary['50']).toBe('#EEF4FC');
    expect(primary['950']).toBe('#031026');
  });

  it('mock_blue_2563EB는_더이상_primary에_없다', () => {
    // 회귀: 구 mock 톤이 남아있으면 실패
    expect(JSON.stringify(colors.primary)).not.toContain('#2563EB');
  });

  it('secondary_success_warning_danger_info가_KRDS_값이다', () => {
    // 문자열 또는 {DEFAULT} 형태 모두 허용
    const flat = (v: unknown): string =>
      typeof v === 'string' ? v : (asObj(v).DEFAULT as string);
    expect(flat(colors.secondary)).toBe('#1850D7');
    expect(flat(colors.success)).toBe('#117C44');
    expect(flat(colors.warning)).toBe('#C25700');
    expect(flat(colors.danger)).toBe('#D1322C');
    expect(flat(colors.info)).toBe('#0F4C97');
  });

  it('neutral이_KRDS_회색_스케일이다', () => {
    const neutral = asObj(colors.neutral);
    expect(neutral.DEFAULT).toBe('#3F4956');
    expect(neutral['50']).toBe('#FAFBFC');
    expect(neutral['200']).toBe('#E1E5EA');
    expect(neutral['900']).toBe('#1E252D');
  });

  it('기존_컴포넌트가_참조하는_색_별칭이_보존된다', () => {
    // accent / bgLight / border 는 소스 전반에서 사용 중 — 삭제 시 회귀
    expect(colors.accent).toBeDefined();
    expect(colors.bgLight).toBeDefined();
    expect(colors.border).toBeDefined();
  });
});

describe('KRDS 디자인 토큰 — 폰트', () => {
  it('sans는_Pretendard_우선이다', () => {
    expect(fontFamily.sans[0]).toBe('Pretendard');
  });

  it('mono는_D2Coding_우선이다', () => {
    expect(fontFamily.mono[0]).toBe('D2Coding');
  });
});

describe('KRDS 디자인 토큰 — 타이포/모양/모션', () => {
  it('KRDS_타이포_스케일이_추가된다', () => {
    const flatSize = (v: unknown): string =>
      Array.isArray(v) ? (v[0] as string) : (v as string);
    expect(flatSize(fontSize['display-xl'])).toBe('56px');
    expect(flatSize(fontSize['title-lg'])).toBe('24px');
    expect(flatSize(fontSize['body-md'])).toBe('16px');
    expect(flatSize(fontSize.label)).toBe('12px');
  });

  it('기존_fontSize_토큰이_보존된다', () => {
    // text-sub(130+회), text-page-title 등 광범위 사용 — 삭제 시 회귀
    expect(fontSize.sub).toBeDefined();
    expect(fontSize['page-title']).toBeDefined();
    expect(fontSize['section-title']).toBeDefined();
    expect(fontSize.body).toBeDefined();
  });

  it('borderRadius_토큰이_KRDS_값이다', () => {
    expect(borderRadius.sm).toBe('4px');
    expect(borderRadius.md).toBe('6px');
    expect(borderRadius.lg).toBe('8px');
    expect(borderRadius.full).toBe('9999px');
  });

  it('boxShadow_토큰이_KRDS_값이다', () => {
    expect(boxShadow.sm).toContain('rgba(14,21,40,0.06)');
    expect(boxShadow.md).toContain('rgba(14,21,40,0.08)');
    expect(boxShadow.lg).toContain('rgba(14,21,40,0.12)');
  });

  it('transition_duration_timing_토큰이_추가된다', () => {
    const dur = fullConfig.theme.transitionDuration as Record<string, string>;
    const timing = fullConfig.theme.transitionTimingFunction as Record<string, string>;
    expect(dur.fast).toBe('120ms');
    expect(dur.slow).toBe('320ms');
    expect(timing.standard).toBe('cubic-bezier(0.2,0,0,1)');
    expect(timing.emphasized).toBe('cubic-bezier(0.3,0,0,1)');
  });
});

describe('폰트 자가호스팅 — CDN 0', () => {
  it('진입점이_로컬_폰트_CSS를_import한다', () => {
    const main = readFileSync(path.join(repoRoot, 'src/main.tsx'), 'utf-8');
    expect(main).toMatch(/pretendard\/.*\.css/);
    expect(main).toMatch(/d2coding\/.*\.css/);
  });

  it('소스에_폰트_CDN_링크가_없다', () => {
    // global.css / main.tsx 에 폰트 CDN URL 이 없어야 함
    const globalCss = readFileSync(path.join(repoRoot, 'src/styles/global.css'), 'utf-8');
    const main = readFileSync(path.join(repoRoot, 'src/main.tsx'), 'utf-8');
    const cdnPattern = /(fastly|jsdelivr|googleapis|gstatic|cdn\.jsdelivr|fonts\.google)/i;
    expect(globalCss).not.toMatch(cdnPattern);
    expect(main).not.toMatch(cdnPattern);
  });
});
