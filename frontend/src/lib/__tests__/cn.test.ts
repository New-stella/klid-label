// `cn()` 의 tailwind-merge 커스텀 폰트 크기 그룹 등록 가드.
//
// 배경: tailwind-merge 기본 설정은 `text-*` 중 <b>t-shirt 스케일만</b> `font-size` 로 인정하고
// 나머지(`text-body`·`text-sub` 등 프로젝트 커스텀 토큰)를 <b>`text-color` 로 오인</b>한다.
// 그 결과 뒤따르는 색 클래스에 밀려 크기 클래스가 <b>조용히 삭제</b>됐다(공통 Textarea/Select 가
// 자기 기본 문자열에서 자기 크기를 잃던 실사고). `src/lib/cn.ts` 가 이 토큰들을 `font-size`
// 그룹에 등록해 고쳤고, 이 파일이 그 계약을 고정한다.
//
// ⚠ mutation 확인: `cn.ts` 의 `extend.classGroups['font-size']` 등록을 빼면
//    "색과 공존" 계열 단언이 전부 FAIL 해야 한다.

import { describe, expect, it } from 'vitest';

import { cn } from '@/lib/cn';

// 진실원은 tailwind 설정이다 — 토큰 목록을 여기에 다시 적지 않고 직접 읽어 드리프트를 막는다.
// `tailwind.config.js` 는 tsconfig `include`(src) 밖의 순수 JS 설정 파일이라 타입 선언이 없다.
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-expect-error — 설정 파일에 타입 선언이 없다(의도적). 아래에서 필요한 형태로 좁혀 쓴다.
import tailwindConfig from '../../../tailwind.config.js';

/** tailwind-merge 기본 검증기가 이미 인식하는 표준 스케일(등록 불필요). */
const STANDARD_SCALE = ['xs', 'sm', 'base', 'lg', 'xl', '2xl', '3xl'];

const fontSizeKeys = Object.keys(
  (tailwindConfig as { theme: { extend: { fontSize: Record<string, unknown> } } }).theme.extend
    .fontSize,
);
const customTokens = fontSizeKeys.filter((k) => !STANDARD_SCALE.includes(k));

describe('cn() — 커스텀 폰트 크기 토큰이 색 클래스에 삼켜지지 않는다', () => {
  it('text-body_는_뒤따르는_색_클래스와_공존한다', () => {
    const out = cn('text-body', 'text-gray-900').split(' ');
    expect(out).toContain('text-body');
    expect(out).toContain('text-gray-900');
  });

  it('text-sub_도_뒤따르는_색_클래스와_공존한다', () => {
    const out = cn('text-sub', 'text-gray-500').split(' ');
    expect(out).toContain('text-sub');
    expect(out).toContain('text-gray-500');
  });

  it('★tailwind_설정의_커스텀_fontSize_토큰_전량이_font-size_그룹으로_등록돼_있다', () => {
    // 설정에 새 토큰을 추가하고 cn.ts 등록을 빠뜨리면 여기서 잡힌다.
    expect(customTokens.length).toBeGreaterThan(0);
    const swallowed = customTokens.filter(
      (token) => !cn(`text-${token}`, 'text-gray-900').split(' ').includes(`text-${token}`),
    );
    expect(swallowed, `cn.ts 의 CUSTOM_FONT_SIZE_TOKENS 에 누락된 토큰: ${swallowed.join(', ')}`).toEqual(
      [],
    );
  });

  it('같은_크기_그룹끼리는_정상_충돌해서_뒤엣것만_남는다', () => {
    const out = cn('text-body', 'text-sm').split(' ');
    expect(out).toContain('text-sm');
    expect(out).not.toContain('text-body');
  });

  it('커스텀_토큰끼리도_뒤엣것만_남는다', () => {
    const out = cn('text-body-lg', 'text-sub').split(' ');
    expect(out).toContain('text-sub');
    expect(out).not.toContain('text-body-lg');
  });

  it('표준_스케일_동작은_회귀하지_않는다', () => {
    // 색과는 공존, 같은 그룹끼리는 뒤엣것 승리 — tailwind-merge 기본 동작 그대로.
    const withColor = cn('text-xs', 'text-gray-700').split(' ');
    expect(withColor).toContain('text-xs');
    expect(withColor).toContain('text-gray-700');

    const sameGroup = cn('text-xs', 'text-lg').split(' ');
    expect(sameGroup).toEqual(['text-lg']);

    // 크기 외 그룹(여백·레이아웃)도 그대로 병합된다.
    expect(cn('px-2 py-1', 'px-4').split(' ')).toEqual(['py-1', 'px-4']);
  });

  it('크기와_무관한_클래스는_커스텀_토큰을_건드리지_않는다', () => {
    expect(cn('text-body', 'resize-y').split(' ')).toEqual(['text-body', 'resize-y']);
  });
});
