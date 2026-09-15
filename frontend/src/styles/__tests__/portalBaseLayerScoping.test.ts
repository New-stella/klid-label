// 회귀 가드 — 포털 채널에서 전역 리셋이 «마운트 앵커 안으로 좁혀진다». [@design INT-013]
//
// ## 무엇을 지키나 (2026-09-15 개발망 실측 사고)
//
// 저작도구를 포털 화면에 한 번 마운트하면 포털(KRDS) UI 가 깨졌다 — GNB·로고·breadcrumb·푸터
// 로고에 «검은 테두리»가 생기고 푸터 레이아웃이 풀렸다. 우리 CSS 링크 하나만 꺼 보면 즉시
// 복구됐다. 원인은 Tailwind Preflight 의 `*{border:0 solid}` 가 KRDS 요소의 `border-style` 을
// `none` → `solid` 로 바꾼 것이다(그 자리는 `border-width` 만 있고 style 은 브라우저 기본값에
// 맡겨져 있었다 — 숨어 있던 굵기가 그대로 그려졌다).
//
// ★ 그때까지의 전제 *"리셋을 `@layer` 안에 두면 Host 가 안전하다"* 가 틀렸다. **계층은
//   우선순위 장치이지 격리 장치가 아니다** — Host 가 아예 선언하지 않은 속성은 경쟁자가 없어
//   레이어 안의 우리 규칙이 그대로 먹는다. 격리는 «선택자 범위»로만 얻어진다.
//
// ## ★★ 두 수치를 «나란히» 본다 — 한쪽만 보면 성공과 실패가 구분되지 않는다
//
//   포털 0건 / 관제 6건  → 성공
//   포털 0건 / 관제 0건  → **실패**(관제 채널이 리셋을 잃었다. 그쪽은 문서를 소유한 독립 앱이라
//                              전역 리셋이 정상이고 «필요하다»)
//   포털 6건 / 관제 6건  → **실패**(아무것도 고쳐지지 않았다)
//
// 변경 전 실측 기준선(두 채널 모두 빌드해 잰 값):
//   포털 872,801 B · `@layer base` 4,032 B · 규칙 53 · 전역 선택자 **6건**
//   관제 843,393 B · `@layer base` 4,032 B · 규칙 53 · 전역 선택자 **6건**
//
// ## 왜 산출물이 아니라 «후처리기»를 직접 돌리나
//
// 두 채널의 산출 CSS 를 동시에 재려면 빌드를 두 번 떠야 한다(각 1분 내외). 단위 시험이 할 일이
// 아니고, `dist/` 에는 마지막에 돌린 한 채널의 것만 남아 «나란히» 볼 수도 없다. 그래서 산출물을
// 만드는 «그 후처리기»에 **실제 입력**(설치된 `tailwindcss/preflight.css` 원문 + `global.css` 의
// `@layer base` 블록 원문)을 넣어 두 채널을 한자리에서 잰다. 입력이 사본이 아니라 원문이므로
// Tailwind 버전업으로 리셋이 바뀌면 이 가드가 그대로 따라간다.

import { readFileSync } from 'node:fs';
import path from 'node:path';

import postcss, { parse } from 'postcss';
import { describe, expect, it } from 'vitest';

import { PORTAL_EMBED_ANCHOR_CLASS } from '@/lib/portalEmbedAnchor';

import scopePortalBaseLayer, { __testing } from '../../../postcss/scope-portal-base-layer.js';

const FRONTEND_ROOT = path.resolve(__dirname, '../../..');
const GLOBAL_CSS = readFileSync(path.join(FRONTEND_ROOT, 'src/styles/global.css'), 'utf-8');
const PLUGIN_SRC = readFileSync(
  path.join(FRONTEND_ROOT, 'postcss/scope-portal-base-layer.js'),
  'utf-8',
);
const POSTCSS_CONFIG = readFileSync(path.join(FRONTEND_ROOT, 'postcss.config.js'), 'utf-8');
const PREFLIGHT = readFileSync(
  path.join(FRONTEND_ROOT, 'node_modules/tailwindcss/preflight.css'),
  'utf-8',
);

/**
 * 「핵심 전역 선택자」 6건 — 문서 전체를 때리는, 이번에 좁혀야 하는 바로 그것들.
 *
 * ⚠ 마지막 둘은 preflight 가 아니라 **우리 `global.css`** 의 것이다(`*` 는 preflight 와
 *   같은 모양으로 두 번 나온다). 그래서 6건이다.
 */
const DOCUMENT_GLOBAL_SELECTORS = [
  '*',
  'html',
  ':host',
  'body',
  '#root',
  'ol',
  'ul',
  'menu',
];

/** `@layer base { … }` 블록의 «본문»을 모두 뽑는다(중괄호 균형 맞춤). */
function extractBaseLayerBodies(css: string): string[] {
  const bodies: string[] = [];
  let from = 0;
  for (;;) {
    const at = css.indexOf('@layer base', from);
    if (at < 0) break;
    const open = css.indexOf('{', at);
    // `@layer theme, base, components;` 처럼 본문 없는 선언은 건너뛴다.
    const semi = css.indexOf(';', at);
    if (open < 0 || (semi >= 0 && semi < open)) {
      from = at + 1;
      continue;
    }
    let depth = 0;
    let i = open;
    for (; i < css.length; i++) {
      if (css[i] === '{') depth++;
      else if (css[i] === '}') {
        depth--;
        if (depth === 0) break;
      }
    }
    bodies.push(css.slice(open + 1, i));
    from = i + 1;
  }
  return bodies;
}

/**
 * 산출물의 `@layer base` 와 «같은 입력» — 설치된 preflight 원문 + 우리 base 블록 원문.
 * (Tailwind 는 `@import … layer(base)` 를 이렇게 한 블록으로 펼친다.)
 */
const OUR_BASE_BODIES = extractBaseLayerBodies(GLOBAL_CSS);
const FIXTURE = `@layer base {\n${PREFLIGHT}\n${OUR_BASE_BODIES.join('\n')}\n}\n`;

function run(css: string, enabled: boolean): string {
  return postcss([scopePortalBaseLayer({ enabled })]).process(css, { from: undefined }).css;
}

/** `@layer base` 안 규칙들의 선택자 목록. */
function baseSelectors(css: string): string[] {
  const out: string[] = [];
  parse(css).walkAtRules('layer', (atRule) => {
    if (!atRule.nodes) return;
    atRule.walkRules((rule) => {
      out.push(rule.selector.replace(/\s+/g, ' ').trim());
    });
  });
  return out;
}

/** 스코프가 «전혀 없는» 문서 전역 선택자의 건수. */
function countUnscopedGlobals(css: string): number {
  return baseSelectors(css).filter((selector) => {
    if (selector.includes(PORTAL_EMBED_ANCHOR_CLASS)) return false;
    return selector
      .split(',')
      .map((s) => s.trim())
      .some((s) => DOCUMENT_GLOBAL_SELECTORS.includes(s));
  }).length;
}

const PORTAL_OUT = run(FIXTURE, true);
const CONTROL_OUT = run(FIXTURE, false);

describe('포털 채널 — 전역 리셋의 적용 범위', () => {
  describe('0) 검사기가 실제로 돌았다 (거짓 0건 방지)', () => {
    it('입력에_문서_전역_선택자가_실재한다', () => {
      // 이 대조가 없으면 아래 「포털 0건」이 «입력이 비어서» 통과할 수 있다.
      expect(FIXTURE.length).toBeGreaterThan(4000);
      expect(baseSelectors(FIXTURE).length).toBeGreaterThan(40);
      expect(countUnscopedGlobals(FIXTURE)).toBe(6);
    });

    it('우리_base_블록이_두_개_다_잡혔다', () => {
      // 하나만 잡히면 v4 보정 3종이 검사 대상에서 통째로 빠진다.
      expect(OUR_BASE_BODIES).toHaveLength(2);
      expect(FIXTURE).toContain('#root');
      expect(FIXTURE).toContain('cursor: pointer');
    });
  });

  describe('★ 두 채널을 나란히 — AC-1 / AC-2', () => {
    it('AC-1_포털_채널은_문서_전역_선택자가_0건이다', () => {
      expect(countUnscopedGlobals(PORTAL_OUT)).toBe(0);
    });

    it('AC-2_관제_채널은_6건_그대로다_양성_대조', () => {
      // ⚠ 둘 다 0 이면 성공이 아니라 «관제가 깨진 것»이다.
      expect(countUnscopedGlobals(CONTROL_OUT)).toBe(6);
    });

    it('★관제_채널_산출물은_한_글자도_바뀌지_않는다', () => {
      expect(CONTROL_OUT).toBe(FIXTURE);
    });
  });

  describe('① 우선순위를 바꾸지 않는다 — `:where()` 로만 좁힌다', () => {
    it('덧붙인_조건이_전부_where_안에_있다', () => {
      // `:where()` 를 빠뜨리면 리셋이 «유틸리티를 이겨» 우리 화면이 통째로 깨진다.
      const bare = PORTAL_OUT.split(`.${PORTAL_EMBED_ANCHOR_CLASS}`).filter((_, i) => i > 0).length;
      const inWhere = (
        PORTAL_OUT.match(new RegExp(`:where\\([^)]*\\.${PORTAL_EMBED_ANCHOR_CLASS}`, 'g')) ?? []
      ).length;
      expect(inWhere).toBeGreaterThan(0);
      // 앵커 이름이 나오는 자리는 모두 `:where(...)` 안이다.
      for (const selector of baseSelectors(PORTAL_OUT)) {
        if (!selector.includes(PORTAL_EMBED_ANCHOR_CLASS)) continue;
        expect(selector).toMatch(new RegExp(`:where\\([^)]*\\.${PORTAL_EMBED_ANCHOR_CLASS}`));
      }
      expect(bare).toBeGreaterThan(0);
    });

    it('모든_규칙이_앵커_안으로_들어갔다', () => {
      for (const selector of baseSelectors(PORTAL_OUT)) {
        expect(selector).toContain(PORTAL_EMBED_ANCHOR_CLASS);
      }
    });
  });

  describe('② 앵커 «자신»도 포함한다', () => {
    it('자기자신_더하기_하위_형태를_쓴다', () => {
      // `:where(ANCHOR) *` 만 쓰면 앵커 요소 자체가 box-sizing 등을 못 받아 레이아웃이 어긋난다.
      // ⚠ 원문이 아니라 «선택자»를 본다 — 주석이 이 형태를 설명하고 있어 원문 검색은
      //   스코프를 통째로 망가뜨린 변이도 통과시킨다(실제로 그렇게 짰다가 변이를 놓쳤다).
      const selfAndDescendants = `:where(.${PORTAL_EMBED_ANCHOR_CLASS},.${PORTAL_EMBED_ANCHOR_CLASS} *)`;
      expect(baseSelectors(PORTAL_OUT).some((s) => s.includes(selfAndDescendants))).toBe(true);
    });
  });

  describe('③ 조상 선택자는 개별 처리한다 — 기계적 접두가 뜻을 바꾸는 것들', () => {
    const portalSelectors = baseSelectors(PORTAL_OUT);

    it('문서_높이_규칙은_포털에서_내보내지_않는다', () => {
      // 문서를 소유하지 않고 `#root` 는 존재조차 하지 않는다. 높이는 앵커의 `h-full` 이 담당한다.
      // ⚠ 원문 검색이 아니라 «선택자» 검사다 — 주석이 그 이름을 설명하고 있어 원문에는 남는다.
      expect(baseSelectors(FIXTURE).some((s) => s.includes('#root'))).toBe(true);
      expect(baseSelectors(PORTAL_OUT).some((s) => s.includes('#root'))).toBe(false);
    });

    it('상속되는_기본값은_앵커_자신에게만_건다', () => {
      // 하위 전체에 뿌리면 «상속이 끊긴다» — 예: `pre{font-family:D2Coding}` 안의 `<span>` 이
      // 물려받아야 할 고정폭 글꼴 대신 직접 걸린 리셋 글꼴을 쓴다.
      const selfOnly = portalSelectors.filter(
        (s) => s === `:where(.${PORTAL_EMBED_ANCHOR_CLASS})`,
      );
      // `html,:host`(preflight) 와 `body`(우리) 둘.
      expect(selfOnly).toHaveLength(2);
      // 어떤 선택자도 `html` 을 주어로 갖지 않는다(앵커의 조상이라 그대로 두면 Host 를 때린다).
      expect(portalSelectors.some((s) => /(^|[\s,>+~])html\b/.test(s))).toBe(false);
    });

    it('★모르는_문서_수준_선택자를_만나면_빌드를_세운다', () => {
      // 조용히 잘못 좁히면 Host UI 파손으로 나타나고 인과는 며칠 뒤에나 드러난다.
      expect(() =>
        run('@layer base{html main{color:red}}', true),
      ).toThrow(/문서 수준 선택자/);
    });
  });

  describe('④ 두 번 돌려도 같다 (중복 적용 방지)', () => {
    it('멱등이다', () => {
      expect(run(PORTAL_OUT, true)).toBe(PORTAL_OUT);
    });
  });

  describe('⑤ 배선 — 후처리기가 Tailwind «뒤»에 있다', () => {
    it('postcss_설정이_두_플러그인을_순서대로_등록한다', () => {
      // 앞에 두면 `@import`(preflight)가 아직 펼쳐지지 않아 좁힐 규칙이 존재하지 않는다.
      const tailwindAt = POSTCSS_CONFIG.indexOf('tailwindcss()');
      const scopeAt = POSTCSS_CONFIG.indexOf('scopePortalBaseLayer()');
      expect(tailwindAt).toBeGreaterThan(-1);
      expect(scopeAt).toBeGreaterThan(tailwindAt);
      expect(POSTCSS_CONFIG).toContain('./postcss/scope-portal-base-layer.js');
    });

    it('채널_판정이_vite_설정과_같은_키를_읽는다', () => {
      // 판정을 두 벌로 만들지 않는다 — 키가 갈리면 포털 산출물이 조용히 안 좁혀진다.
      expect(PLUGIN_SRC).toContain("process.env.VITE_BUILD_CHANNEL === 'portal'");
    });

    it('OnceExit_에서_돈다', () => {
      // `@tailwindcss/postcss` 는 모든 일을 `Once` 에서 끝낸다(실측). `OnceExit` 여야 그 뒤다.
      expect(scopePortalBaseLayer({ enabled: true })).toHaveProperty('OnceExit');
    });
  });

  describe('⑥ 앵커 이름 — 세 곳의 값이 같다', () => {
    it('플러그인_전역CSS_TS상수가_같은_값을_쓴다', () => {
      // 이 파일은 Node 에서 평가돼 TS 를 import 하지 못해 리터럴을 둘 수밖에 없다.
      // 값이 갈리면 격리가 «조용히» 풀린다.
      expect(__testing.ANCHOR_CLASS).toBe(PORTAL_EMBED_ANCHOR_CLASS);
      expect(GLOBAL_CSS).toContain(`.${PORTAL_EMBED_ANCHOR_CLASS} {`);
    });
  });
});
