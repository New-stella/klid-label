// 죽은 브레이크포인트 접두어(`sm:`/`lg:`/`2xl:`)가 소스에 다시 유입되는 것을 막는다.
//
// ★ 왜 필요한가 — 이 결함은 아무 신호도 내지 않는다
//   `tailwind.config.js` 는 `theme.screens` 를 `extend` 가 아니라 **교체**한다(md/xl 두 개).
//   그래서 Tailwind 기본 접두어인 `sm:`/`lg:`/`2xl:` 은 **CSS 가 생성조차 되지 않고**
//   클래스 문자열에만 남는다. 타입 오류도, 런타임 경고도, 콘솔 메시지도 없다.
//   jsdom 단위 테스트도 원리상 못 잡는다 — className 문자열은 그대로 붙어 있고
//   jsdom 은 CSS 를 적용하지 않으므로 "적용됐는지"를 물어볼 대상 자체가 없다.
//   실제 피해: 단축키 도움말 표가 `sm:grid-cols-3` 미적용으로 23행 1열(높이 1116px)이 되어
//   화면 밖으로 잘렸고, AI 탐지 모달은 `sm:grid-cols-2` 미적용으로 좌우 분리가 성립하지 않아
//   우측 450px 가 통째로 비어 있었다. 둘 다 사람이 눈으로 발견할 때까지 남아 있었다.
//
// ★ 판정 기준은 설정에서 파생시킨다 — 하드코딩하지 않는다
//   "죽은 접두어" = (브레이크포인트 후보) − (`theme.screens` 에 실제로 정의된 것).
//   나중에 `screens` 에 `sm` 이 추가되면 이 가드는 **스스로 sm 을 허용**한다.
//   반대로 `md` 를 빼면 그 즉시 `md:` 사용처가 전부 잡힌다. 설정과 소스가 갈라지지 않는다.
//
// ⚠ mutation 확인 절차: 아무 파일에서 `md:grid-cols-2` 를 `sm:grid-cols-2` 로 되돌리면
//   첫 번째 테스트가 그 파일:줄 을 지목하며 FAIL 해야 한다. (실제로 확인함)

import fs from 'node:fs';
import path from 'node:path';

import resolveConfig from 'tailwindcss-v3-compat/resolveConfig';
import { describe, expect, it } from 'vitest';

// tailwind.config.js 는 타입 선언이 없는 plain JS(ESM default export)
// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig from '../../tailwind.config.js';

const SRC_DIR = path.resolve(__dirname, '..');

/**
 * 소스에 등장할 수 있는 브레이크포인트 접두어 후보(Tailwind 기본 스케일).
 * 이 목록은 "무엇을 검사할지"만 정하고, "무엇이 유효한지"는 설정이 정한다.
 */
const BREAKPOINT_CANDIDATES = ['xs', 'sm', 'md', 'lg', 'xl', '2xl', '3xl'];

const liveScreens = Object.keys(
  (resolveConfig(tailwindConfig as never).theme.screens ?? {}) as Record<string, unknown>,
);
const deadPrefixes = BREAKPOINT_CANDIDATES.filter((bp) => !liveScreens.includes(bp));

/**
 * 죽은 접두어가 **유틸리티 클래스로** 쓰인 곳만 잡는다.
 *
 * - 앞: 클래스 경계여야 한다(`[\w-]` 금지) → `max-w-sm`·`text-2xl` 오검출 차단.
 * - 뒤: 콜론 **직후에 공백이 없어야** 한다 → 객체 키(`sm: 'h-1.5'`)·타입 표기(`sm: string`)
 *   같은 크기 변형 맵을 걸러낸다. 이 프로젝트의 `sm:` 등장 대다수가 실은 이 크기 맵이다.
 * - 문자열 리터럴 `'sm:'`(가드 단언용)도 콜론 뒤가 따옴표라 자연히 빠진다.
 */
const deadRe = new RegExp(`(?<![\\w-])(${deadPrefixes.join('|')}):(?=[a-z0-9[(-])`);

/** src 하위 모든 .ts/.tsx 를 재귀 수집한다(클래스 문자열은 .ts 상수 파일에도 산다). */
function sourceFiles(dir: string): string[] {
  return fs
    .readdirSync(dir, { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.tsx?$/.test(f))
    .map((f) => path.join(dir, f));
}

/**
 * 주석을 걷어낸 코드 줄만 돌려준다 — 이 결함을 **설명하는 주석**에 `sm:` 이 그대로 등장하므로
 * (예: "구 `sm:grid-cols-2` 는 한 번도 적용된 적이 없다"), 걷어내지 않으면 <b>주석이 곧 위반</b>이 된다.
 * 블록 주석은 줄 수를 보존하며 지워 줄번호가 어긋나지 않게 한다.
 * `//` 는 `https://` 를 오인하지 않도록 앞에 `:` 가 없을 때만 주석으로 본다.
 * (구현은 LadderTypography.test.tsx 의 같은 헬퍼와 동일한 규약이다.)
 */
function codeLines(file: string): Array<{ no: number; text: string }> {
  const stripped = fs
    .readFileSync(file, 'utf-8')
    .replace(/\{?\/\*[\s\S]*?\*\/\}?/g, (m) => '\n'.repeat((m.match(/\n/g) ?? []).length))
    .replace(/(?<!:)\/\/.*$/gm, '');
  return stripped.split('\n').map((text, i) => ({ no: i + 1, text }));
}

describe('반응형 브레이크포인트 — 죽은 접두어 유입 차단', () => {
  it('★설정에_정의되지_않은_브레이크포인트_접두어가_src에_없다', () => {
    // given: 설정이 정의한 살아있는 브레이크포인트 밖의 접두어
    expect(deadPrefixes.length, '검사할 죽은 접두어가 하나도 없다면 가드가 무의미해진다').
      toBeGreaterThan(0);

    // when: src 전체를 훑는다
    const offenders: string[] = [];
    for (const file of sourceFiles(SRC_DIR)) {
      // 이 가드 자신은 제외한다 — 아래 세 번째 테스트가 "진짜 위반"을 **의도적으로 문자열
      // 리터럴로** 들고 있어(가드가 살아있음을 증명하는 양성 대조군) 스스로를 잡는다.
      if (path.basename(file) === path.basename(__filename)) continue;
      for (const { no, text } of codeLines(file)) {
        if (deadRe.test(text)) {
          offenders.push(`${path.relative(SRC_DIR, file)}:${no}  ${text.trim().slice(0, 100)}`);
        }
      }
    }

    // then: 한 건도 없어야 한다
    expect(
      offenders,
      `설정에 없는 브레이크포인트라 CSS 가 생성되지 않는다 — 아무 오류 없이 조용히 무시된다.\n` +
        `살아있는 브레이크포인트: ${liveScreens.join(' / ')} · 죽은 접두어: ${deadPrefixes.join(' / ')}\n` +
        `${offenders.join('\n')}`,
    ).toEqual([]);
  });

  it('살아있는_브레이크포인트는_md와_xl_두_개다', () => {
    // 위 테스트의 판정 근거를 고정한다 — screens 가 바뀌면 여기서 먼저 드러나
    // "가드가 아무것도 검사하지 않게 된" 상태를 조용히 통과시키지 않는다.
    expect(liveScreens).toEqual(['md', 'xl']);
    expect(deadPrefixes).toContain('sm');
    expect(deadPrefixes).toContain('lg');
    expect(deadPrefixes).toContain('2xl');
  });

  it('크기_변형_맵과_설명_주석은_위반으로_잡지_않는다', () => {
    // 오탐 방어를 고정한다. 이 셋이 잡히기 시작하면 가드가 소음이 되어 무력화된다.
    expect(deadRe.test(`  sm: 'text-label px-2 py-0.5',`)).toBe(false); // 크기 변형 맵 키
    expect(deadRe.test(`  size?: 'sm' | 'md'`)).toBe(false); // 타입 표기
    expect(deadRe.test(`className="max-w-sm text-2xl"`)).toBe(false); // 접두어가 아닌 크기 토큰
    expect(deadRe.test(`expect(cls).not.toContain('sm:')`)).toBe(false); // 가드 단언 문자열

    // 반대로 진짜 위반은 반드시 잡는다(가드가 살아있음을 같은 자리에서 증명한다).
    expect(deadRe.test(`<div className="grid grid-cols-1 sm:grid-cols-2">`)).toBe(true);
    expect(deadRe.test(`className={cn('grid', 'lg:grid-cols-6')}`)).toBe(true);
    expect(deadRe.test(`<div className="2xl:flex-row">`)).toBe(true);
  });
});
