// **src 전역**이 borderRadius 토큰 4단(sm 4 / md 6 / lg 8 / full) 안에서만 모서리를 적는지
// 소스 레벨에서 고정한다.
//
// ★ 왜 필요한가
//   `tailwind.config.js` 는 `borderRadius` 를 `theme.extend` 아래 4단만 정의한다. `extend` 라
//   **Tailwind 기본 스케일이 그대로 살아 있어**, 토큰 밖 단(xl 이상)을 써도 클래스가 정상
//   생성되어 12px 로 **오류 없이 조용히** 렌더된다. 타입 오류도 런타임 경고도 없고, jsdom
//   단위 테스트도 원리상 못 잡는다(className 문자열은 그대로 붙어 있고 jsdom 은 CSS 를
//   적용하지 않는다). 같은 성질의 사고를 음영 축에서 이미 겪었다.
//   실제 피해: 포털 화면의 카드·리스트 컨테이너 5곳이 시안(8px)이 아닌 12px 로 새고 있었다.
//   저장소의 시안 CSS 78개 파일은 전부 sm 4 / md 6 / lg 8 / full 만 정의하며, 12px 도
//   `--radius-xl` 도 전 시안에 0건이다.
//
// ★ 스코프는 `src` 전역이다
//   구 판(`components/common/__tests__/CommonRadiusToken.test.ts`)은 스코프가 공용 컴포넌트
//   뿐이었다. 그때는 포털에 위반 5건이 남아 있어 좁혀 둔 것이고, 그 5건을 정리하면서 구멍을
//   메웠다. **다시 좁히지 말 것** — 이 결함은 아무 신호도 내지 않으므로 스코프 밖은 곧 사각이다.
//
// ★ 주석 안의 위반도 잡는다 — 오탐이 아니라 의도다
//   Tailwind 의 content 추출기는 소스를 **구문 분석하지 않고 정규식으로 훑는다**. 그래서 주석에
//   적힌 `rounded-` + `xl` 도 후보로 잡혀 **번들에 그 클래스를 되살린다**. 죽은 브레이크포인트
//   가드(`deadBreakpoints.test.ts`)가 주석을 걷어내는 것과 **반대로 가는 것이 맞다** — 그쪽은
//   접두어가 죽어 CSS 가 아예 생성되지 않지만, 이쪽은 살아 있는 기본 클래스라 실제로 새어 나온다.
//   두 가드를 "일관성"을 이유로 통일하지 말 것.
//
// ★ 그래서 이 파일은 금지 클래스명을 **통째로 적지 않는다**
//   정규식을 분해해 적고, 양성 대조군도 문자열을 이어 붙여 만든다. 여기에 리터럴을 적으면
//   가드 자신이 그 클래스를 번들에 되살린다(색 축에서 실제로 겪은 사고). 자기 자신을 스캔에서
//   제외하는 이유도 같다 — 위 설명들이 곧 위반으로 잡히기 때문이다.
//
// ⚠ 이 스캔이 못 보는 것
//   1. 동적으로 조립되는 클래스명(`` `rounded-${size}` ``) — 소스에 문자열이 통째로 없으면 못 잡는다.
//   2. CSS 파일의 `border-radius` 직접 선언 · 인라인 `style` 런타임 값.
//   3. `src` 밖(예: `index.html`) — Tailwind content 글롭은 `./index.html` 도 포함하지만
//      거기엔 유틸리티 클래스를 적지 않는 것이 이 저장소의 관례다.
//   4. 토큰 **값**(lg 가 8px 인가)의 검증은 여기가 아니라 `designTokens.test.ts` 소관이다.
//      이 파일은 "토큰 밖 단을 쓰지 않는가"만 본다.
//
// ⚠ mutation 확인 절차: 아무 파일의 `rounded-lg` 를 토큰 밖 상위 단으로 되돌리면 첫 번째
//   테스트가 그 파일:줄 을 지목하며 FAIL 해야 한다. (실제로 확인함 — PortalHomePage.tsx:156)

import fs from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

// tailwind.config.js 는 타입 선언이 없는 plain JS(ESM default export)
// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig from '../../tailwind.config.js';

const SRC_DIR = path.resolve(__dirname, '..');

/**
 * 토큰 밖 모서리 단 — 정규식을 **분해해** 적는다(위 ★ 참조).
 * `\b` 를 뒤에 두면 `rounded-` + `xl` 이 `-foo` 가 이어 붙은 조합을 오검출하지 않는다.
 */
const OUT_OF_TOKEN_RADIUS = /\brounded-(xl|2xl|3xl)\b/g;

/** 이 가드가 "토큰 밖"이라고 판정하는 단. 아래 두 번째 테스트가 설정과 대조해 고정한다. */
const OUT_OF_TOKEN_STEPS = ['xl', '2xl', '3xl'];

/** src 하위 모든 .ts/.tsx 를 재귀 수집한다(클래스 문자열은 .ts 상수 파일에도 산다). */
function sourceFiles(dir: string): string[] {
  return fs
    .readdirSync(dir, { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.tsx?$/.test(f))
    .map((f) => path.join(dir, f));
}

describe('모서리 반경 — borderRadius 토큰 4단 준수', () => {
  it('★src_전역에_토큰_밖_모서리_단이_없다_전수_스캔', () => {
    // given: src 하위 전체 소스
    const files = sourceFiles(SRC_DIR);
    expect(
      files.length,
      '스캔 대상이 비정상적으로 적다 — 파일 수집이 깨졌는데 0건으로 통과할 뻔했다',
    ).toBeGreaterThan(500);

    // when: 한 줄씩 훑는다(주석도 포함 — 위 ★ 참조)
    const violations: string[] = [];
    for (const file of files) {
      // 이 가드 자신은 제외한다 — 설명 주석과 양성 대조군이 곧 위반으로 잡힌다.
      if (path.basename(file) === path.basename(__filename)) continue;
      fs.readFileSync(file, 'utf-8')
        .split('\n')
        .forEach((line, i) => {
          for (const m of line.matchAll(OUT_OF_TOKEN_RADIUS)) {
            violations.push(
              `${path.relative(SRC_DIR, file)}:${i + 1} — rounded-${m[1]}  ${line.trim().slice(0, 100)}`,
            );
          }
        });
    }

    // then: 한 건도 없어야 한다
    expect(
      violations,
      'borderRadius 토큰은 sm/md/lg/full 4단뿐이다. 그 밖의 단은 Tailwind 기본값으로 폴백해\n' +
        '오류 없이 조용히 렌더된다(xl = 12px):\n' +
        violations.join('\n'),
    ).toEqual([]);
  });

  it('토큰이_정의한_단은_sm_md_lg_full_네_개다', () => {
    // 위 테스트의 **판정 근거**를 고정한다. 설정에 xl 이 추가되면 위 스캔은 정당한 사용처를
    // 위반으로 잡기 시작하는데, 그 모순을 조용히 두지 않고 여기서 먼저 드러낸다.
    const tokens = (tailwindConfig as { theme: { extend: { borderRadius: Record<string, string> } } })
      .theme.extend.borderRadius;

    expect(Object.keys(tokens)).toEqual(['sm', 'md', 'lg', 'full']);
    for (const step of OUT_OF_TOKEN_STEPS) {
      expect(
        tokens,
        `${step} 이 토큰으로 승격됐다면 위 스캔의 금지 목록에서 빼야 한다`,
      ).not.toHaveProperty(step);
    }
  });

  it('정규식이_진짜_위반을_잡고_토큰_단은_잡지_않는다', () => {
    // 가드가 살아있음을 같은 자리에서 증명한다(양성 대조군).
    // ⚠ 리터럴을 그대로 적으면 Tailwind 가 그 클래스를 번들에 되살리므로 **이어 붙여** 만든다.
    const r = 'rounded-';
    for (const step of OUT_OF_TOKEN_STEPS) {
      expect(new RegExp(OUT_OF_TOKEN_RADIUS.source).test(`className="p-4 ${r}${step} border"`)).toBe(
        true,
      );
    }

    // 오탐 방어 — 토큰 4단과 무관한 크기 토큰은 잡지 않는다.
    for (const ok of ['sm', 'md', 'lg', 'full', 'none']) {
      expect(new RegExp(OUT_OF_TOKEN_RADIUS.source).test(`className="${r}${ok}"`)).toBe(false);
    }
    expect(new RegExp(OUT_OF_TOKEN_RADIUS.source).test(`className="max-w-xl text-2xl"`)).toBe(false);
  });
});
