// [@design INT-013]
/**
 * 포털 채널 산출물에서 **전역 리셋의 적용 범위를 우리 마운트 앵커 안으로 좁힌다.**
 *
 * ## 왜 필요한가 (2026-09-15 개발망 실측)
 *
 * 포털은 우리 프론트를 Module Federation Remote 로 «자기 문서 안에» 마운트하고, 우리 CSS 는
 * `bundleAllCSS` 산출물로 Host 문서 `<head>` 에 통째로 꽂힌다. 그 안의 Tailwind Preflight 와
 * 우리 `@layer base` 선언은 선택자가 `html`·`body`·`*`·`ol,ul,menu` 라 **Host 문서 전체**를
 * 때린다. 실제로 포털(KRDS) 화면이 깨졌다 — 상단 GNB·로고·breadcrumb·푸터 로고에 검은
 * 테두리가 생기고 푸터 레이아웃이 풀렸다.
 *
 * 기전: preflight 의 `*{border:0 solid}` 가 `border-style` 을 `none` → `solid` 로 바꾼다.
 * KRDS 는 `border-width` 만 주고 style 은 브라우저 기본값(`none`)에 맡긴 자리가 있는데,
 * 거기 style 이 생기면 **숨어 있던 굵기가 그대로 그려진다.**
 *
 * ## ★ 계층(@layer)은 «격리» 수단이 아니다
 *
 * 「리셋을 `@layer` 안에 두면 Host UI 가 안전하다」는 것이 기존 전제였는데 **틀렸다.**
 * 계층 우선순위는 «같은 요소의 같은 속성»을 두고 맞붙을 때만 작동한다. Host 가 아예 선언하지
 * 않은 속성(`border-style` 이 정확히 그 경우)은 경쟁자가 없어 우리 계층 규칙이 그대로 먹는다.
 * ⇒ **격리는 선택자 범위로만 얻어진다.** 그것이 이 플러그인이 하는 일이다.
 *
 * ## 왜 PostCSS 후처리인가 (대안 기각 근거 — 되살리지 말 것)
 *
 * - **(기각) preflight 의 스코프한 사본을 둔다** — 사본이 «두 번째 진실원»이 되어 Tailwind
 *   버전업 때 조용히 어긋난다. 이 방식은 Tailwind 가 내보낸 것을 그대로 받아 다시 쓰므로
 *   버전업을 자동 추종한다.
 * - **(기각) `@scope`** — `@import` 는 `@scope` 안에 들어가지 못해 성립하지 않는다.
 *
 * `@tailwindcss/postcss` 는 모든 일을 `Once` 에서 끝낸다(실측). 이 플러그인은 `OnceExit` 라
 * **항상 그 뒤**에 돌아 `@import` 가 전부 펼쳐진 결과를 본다.
 *
 * ## 무엇만 건드리나
 *
 * `@layer base { … }` **안의 규칙만**이다. 산출물의 나머지 자리는 손대지 않는다:
 *   - `@layer theme` · `@layer properties` — 커스텀 프로퍼티 «선언»만 담아 요소 모양을 바꾸지 않는다
 *   - 레이어 밖 — 유틸리티(우리가 클래스를 붙인 요소만 때린다) · `:root{--krds-*}` · 앵커 규칙
 *
 * ## 우선순위를 바꾸지 않는다
 *
 * 덧붙이는 것은 **`:where()`** 뿐이라 우선순위가 0 이다. 감싸지 않고 붙이면 리셋이
 * **유틸리티를 이겨** 우리 화면이 통째로 깨진다.
 *
 * ## 앵커 «자신»도 포함한다
 *
 * `:where(ANCHOR, ANCHOR *)` 형태다. 하위만 잡으면 앵커 요소 자체가 `box-sizing` 등을 못 받아
 * 레이아웃이 어긋난다.
 *
 * ## ⚠ 상속되는 속성은 하위 전체에 뿌리면 안 된다
 *
 * `html,:host` 와 우리 `body` 규칙은 **상속되는 기본값**(글꼴·행간·글자색)을 세운다. 이것을
 * `ANCHOR *` 까지 뿌리면 **상속이 끊긴다** — 예컨대 `pre{font-family:D2Coding}` 안의 `<span>`
 * 이 물려받아야 할 고정폭 글꼴 대신, 직접 걸린 리셋 글꼴을 쓰게 된다(직접 적용은 우선순위와
 * 무관하게 상속을 이긴다). 그래서 그 둘만 **앵커 자신에게만** 건다.
 *
 * ## ⚠ 모르는 문서 수준 선택자를 만나면 «실패»한다
 *
 * Tailwind 가 버전업으로 새 `html`/`body` 규칙을 내보내면, 조용히 잘못 좁히는 대신 빌드를
 * 세운다. 조용한 오작동은 Host UI 파손으로 나타나고 그 인과는 며칠 뒤에나 드러난다.
 */

/**
 * 스타일 격리 앵커 — `AuthoringRemote` 가 렌더하는 우리 소유 요소의 클래스.
 *
 * ⚠ 이 파일은 Node 에서 평가돼 TS 상수를 import 할 수 없어 리터럴을 둔다. 값이 갈리지 않도록
 *   회귀 가드(`src/styles/__tests__/portalBaseLayerScoping.test.ts`)가 이 파일 · `global.css` ·
 *   `lib/portalEmbedAnchor.ts` 세 곳의 값이 «같다»를 단언한다.
 */
const ANCHOR_CLASS = 'klid-portal-embed';

/** 앵커 «자신 + 하위 전부» — 상속되지 않는 리셋(여백·테두리·박스모델)의 범위. */
const SCOPE_SELF_AND_DESCENDANTS = `:where(.${ANCHOR_CLASS},.${ANCHOR_CLASS} *)`;

/** 앵커 «자신만» — 상속되는 기본값(글꼴·행간·색)의 범위. 위 ⚠ 절 참조. */
const SCOPE_SELF_ONLY = `:where(.${ANCHOR_CLASS})`;

/**
 * 문서 수준 선택자의 개별 처리표 — 접두만으로는 뜻이 달라지는 것들.
 *
 * 값이 `null` 이면 «포털 채널에서는 내보내지 않는다».
 */
const DOCUMENT_LEVEL_TREATMENT = new Map([
  // preflight — 문서 기본 글꼴·행간·탭크기. 상속 기본값이라 앵커 자신에게만.
  ['html,:host', SCOPE_SELF_ONLY],
  // 우리 것 — 독립 앱에서 문서 높이를 100% 로 펴는 규칙. 포털에서는 문서를 소유하지 않고
  // `#root` 는 존재조차 하지 않는다. 높이는 앵커의 `h-full` 이 담당하므로 **뺀다**.
  ['html,body,#root', null],
  // 우리 것 — 본문 타이포·색·배경. 상속 기본값이라 앵커 자신에게만.
  ['body', SCOPE_SELF_ONLY],
]);

/** 문서 수준을 가리키는 토큰 — 하나라도 있으면 위 표를 반드시 거친다. */
const DOCUMENT_LEVEL_TOKEN = /(^|[\s,>+~([])(html|body|:root|:host|#root)\b/;

/* ───────────────────── 선택자 조각내기 (괄호·대괄호·따옴표 존중) ───────────────────── */

/** 최상위 쉼표로 가른다 — `:is(a,b)` 안의 쉼표에 걸리지 않는다. */
function splitTopLevelCommas(selector) {
  const out = [];
  let depth = 0;
  let quote = null;
  let buf = '';
  for (const ch of selector) {
    if (quote) {
      buf += ch;
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      buf += ch;
      continue;
    }
    if (ch === '(' || ch === '[') depth++;
    else if (ch === ')' || ch === ']') depth--;
    if (ch === ',' && depth === 0) {
      out.push(buf);
      buf = '';
      continue;
    }
    buf += ch;
  }
  out.push(buf);
  return out.map((s) => s.trim()).filter(Boolean);
}

/** 한 복합선택자에서 «주어 화합물»(마지막 결합자 뒤)이 시작하는 자리. */
function subjectStart(complex) {
  let depth = 0;
  let quote = null;
  let start = 0;
  for (let i = 0; i < complex.length; i++) {
    const ch = complex[i];
    if (quote) {
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      continue;
    }
    if (ch === '(' || ch === '[') depth++;
    else if (ch === ')' || ch === ']') depth--;
    else if (depth === 0 && (ch === ' ' || ch === '>' || ch === '+' || ch === '~')) {
      start = i + 1;
    }
  }
  return start;
}

/** 한 글자 콜론으로 쓰는 «옛 표기» 가상요소 — 뒤에 선택자를 더 붙일 수 없다. */
const LEGACY_PSEUDO_ELEMENTS = new Set(['before', 'after', 'first-line', 'first-letter']);

/**
 * 화합물 안에서 «가상요소가 시작하는 자리» — 우리 범위 조건은 그 앞에 들어가야 한다.
 * 가상요소가 없으면 화합물 끝(= 그 자리에 덧붙인다).
 */
function pseudoElementStart(compound) {
  let depth = 0;
  let quote = null;
  for (let i = 0; i < compound.length; i++) {
    const ch = compound[i];
    if (quote) {
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      continue;
    }
    if (ch === '(' || ch === '[') {
      depth++;
      continue;
    }
    if (ch === ')' || ch === ']') {
      depth--;
      continue;
    }
    if (depth !== 0 || ch !== ':') continue;
    if (compound[i + 1] === ':') return i;
    const name = /^[-\w]+/.exec(compound.slice(i + 1));
    if (name && LEGACY_PSEUDO_ELEMENTS.has(name[0].toLowerCase())) return i;
  }
  return compound.length;
}

/** 복합선택자 하나를 우리 범위 안으로 좁힌다. */
function scopeComplexSelector(complex, scope) {
  const at = subjectStart(complex);
  const prefix = complex.slice(0, at);
  const subject = complex.slice(at);
  const insertAt = pseudoElementStart(subject);
  return `${prefix}${subject.slice(0, insertAt)}${scope}${subject.slice(insertAt)}`;
}

/** 선택자 전체(쉼표 목록)를 좁힌다. */
export function scopeSelector(selector, scope = SCOPE_SELF_AND_DESCENDANTS) {
  return splitTopLevelCommas(selector)
    .map((complex) => scopeComplexSelector(complex, scope))
    .join(',');
}

/** 표 조회용 정규화 — 공백만 걷어낸다(`html, :host` 와 `html,:host` 를 같게 본다). */
function normalizeSelectorKey(selector) {
  return selector.replace(/\s*([,>+~])\s*/g, '$1').replace(/\s+/g, ' ').trim();
}

/**
 * 규칙 하나를 어떻게 바꿀지 — `null` 이면 삭제.
 * 이미 좁혀진 규칙은 그대로 돌려준다(중복 적용 방지).
 */
function rewrittenSelectorFor(rule) {
  if (rule.selector.includes(ANCHOR_CLASS)) return rule.selector;

  // 원문은 여러 줄일 수 있다(`html,\n:host`). 줄바꿈·탭을 한 칸으로 접어 결합자 판정이
  // 「공백」 하나만 보면 되게 한다 — 접지 않으면 줄바꿈이 결합자로 읽히지 않아 주어를
  // 엉뚱한 자리에서 잡는다.
  const flattened = rule.selector.replace(/\s+/g, ' ').trim();
  const key = normalizeSelectorKey(flattened);
  if (DOCUMENT_LEVEL_TREATMENT.has(key)) {
    const scope = DOCUMENT_LEVEL_TREATMENT.get(key);
    return scope === null ? null : scope;
  }

  if (DOCUMENT_LEVEL_TOKEN.test(key)) {
    throw new Error(
      `[scope-portal-base-layer] 처리 방법이 정해지지 않은 문서 수준 선택자입니다: "${key}".\n` +
        '이 선택자는 우리 마운트 앵커의 바깥(문서 자체)을 가리키므로 기계적으로 좁히면 뜻이 달라집니다.\n' +
        '포털 채널에서 이 규칙이 무엇을 해야 하는지 정한 뒤 DOCUMENT_LEVEL_TREATMENT 표에 명시하세요.\n' +
        '(조용히 잘못 좁히면 포털 Host UI 가 깨지고 그 인과는 뒤늦게 드러납니다.)',
    );
  }

  return scopeSelector(flattened);
}

/**
 * @param {{ enabled?: boolean }} [options]
 *   `enabled` 를 주지 않으면 **포털 채널 빌드일 때만** 동작한다.
 *   이 파일은 Node 에서 평가되므로 `process.env` 를 읽는다 — `vite.config.ts` 의
 *   `IS_PORTAL_BUILD` 와 **같은 키·같은 방식**이다(Vite 가 빌드 시점에 `import.meta.env` 를
 *   `process.env` 에서 채우므로 브라우저 쪽 판정과 값이 같다).
 */
export default function scopePortalBaseLayer(options = {}) {
  const enabled =
    options.enabled ?? process.env.VITE_BUILD_CHANNEL === 'portal';

  return {
    postcssPlugin: 'scope-portal-base-layer',
    // ⚠ `OnceExit` 여야 한다 — `@tailwindcss/postcss` 는 `Once` 에서 `@import` 를 펼친다.
    OnceExit(root) {
      if (!enabled) return;
      root.walkAtRules('layer', (atRule) => {
        // 선언만 하는 `@layer theme, base, components;` 는 본문이 없다.
        if (!atRule.nodes) return;
        if (normalizeSelectorKey(atRule.params) !== 'base') return;
        atRule.walkRules((rule) => {
          const next = rewrittenSelectorFor(rule);
          if (next === null) {
            rule.remove();
            return;
          }
          rule.selector = next;
        });
      });
    },
  };
}

export const __testing = {
  ANCHOR_CLASS,
  SCOPE_SELF_AND_DESCENDANTS,
  SCOPE_SELF_ONLY,
  DOCUMENT_LEVEL_TREATMENT,
  scopeSelector,
  normalizeSelectorKey,
};
