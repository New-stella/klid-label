/**
 * DS-002 — 포털 채널 디자인 시스템의 Tailwind 투영.
 *
 * ★진실원은 LogiCraft `DS-002` ITEM 본문이다. 이 파일은 그 값을 Tailwind 스케일 이름으로
 *   옮겨 적은 **투영**이며, 값을 여기서 만들어내지 않는다. `DS-002` 자체가 외부 채널
 *   정의 v9 의 **사본**이므로, 값을 고쳐야 하면 여기가 아니라 상대에 제기한다
 *   (`DS-002.dont_rules` — "우리가 이 사본의 값을 고치지 않는다").
 *
 * <h3>왜 채널로 가르나</h3>
 * 관제 채널은 `DS-001`(KRDS), 포털 채널은 `DS-002`(정부 디자인시스템 위에 포털 브랜드를
 * 얹은 2차 층)를 쓴다. **주색·중립 온도·본문 기준 크기·굵기 상한·모서리·그림자가 모두
 * 다르다.** 그런데 **토큰 이름이 같다** — `DS-002.dont_rules` 가 못박는다:
 *
 * > "토큰 이름이 같다고 값을 같은 것으로 다루지 않는다 — 이쪽은 원본 위에 브랜드 색을 덮어썼다."
 *
 * 그래서 이름을 갈라 두 벌을 만들지 않고, **같은 이름의 값을 채널이 정한다**. 호출부
 * (`text-body-md`·`bg-primary-600`·`text-table-header`)는 한 벌로 두고 산출 시점에 값이 갈린다.
 *
 * <h3>왜 산출 시점 분기로 충분한가</h3>
 * 라우트가 이미 산출 시점에 갈린다 — `router/index.tsx` 의 `internalRoutes`/`portalRoutes` 가
 * `IS_PORTAL_CHANNEL_BUILD` 로 갈려, **관제 산출물에는 포털 화면이 없고 포털 산출물에는 관제
 * 화면이 없다.** 즉 한 번들 안에서 두 채널 화면이 동시에 뜨는 형상이 존재하지 않으므로,
 * 토큰도 산출물 한 벌당 한 시스템이면 충분하다(런타임 분기가 필요 없다).
 *
 * <h3>투영하지 못한 것 — 지어내지 않고 남긴다</h3>
 * `DS-002.iteration_guide` 가 "값이 없으면 지어내지 말고 … 확인이 안 되면 비워 두고 정보
 * 부족으로 남긴다"고 규정한다. 아래가 그 목록이며 각 자리에 주석으로 사유를 남겼다.
 *  1. `tertiary` — ITEM 의 scale 이 **4칸뿐이고 단계 이름이 없다**. Tailwind 11단에 어느
 *     자리로 놓을지는 ITEM 이 말하지 않는다 → **투영하지 않는다**(쓰는 곳도 아직 없다).
 *  2. `category-*`(범주 구분색 8슬롯) — `DS-001` 의 규칙이고 `DS-002` 에 대응 규칙이 없다
 *     → 관제 값을 그대로 물려받는다. `DS-002.known_gaps` 의 "스키마가 담지 못하는 도메인
 *     토큰" 에 해당한다.
 *  3. `rowHover`(표 행 hover 표면) — **DS-002 가 규정하지 않는다.** 아래 해당 자리 참조.
 *  4. `shadow.sm/md/lg` — DS-002 의 음영은 `none`/`card-soft`/`dropdown` 세 이름뿐이라
 *     관제 3단과 대응이 서지 않는다 → 이름은 그대로 두고 DS-002 이름을 **더한다**.
 *  5. `screens`(중단점) — DS-002 는 767/1023/1024 를 말하는데 이 저장소는 `md`/`xl` 두 개만
 *     둔다(`deadBreakpoints.test.ts` 가 그 형상을 고정한다) → 이번 라운드 범위 밖.
 */

/** DS-002 `tokens.colors.*.scale` 배열의 자리 → Tailwind 단계 이름. */
const STEPS = ['50', '100', '200', '300', '400', '500', '600', '700', '800', '900', '950'];

/**
 * 11칸 배열을 Tailwind 스케일로 편다. `DEFAULT` 는 ITEM 의 앵커(`hex`) 값이다.
 *
 * ⚠ 배열이 11칸이 아니면 던진다 — 조용히 짧은 스케일을 만들면 `bg-primary-700` 같은
 *   클래스가 **오류 없이 생성되지 않아** 색이 안 붙는다(관제 축에서 실제로 겪은 사고).
 */
function scale(hex, arr) {
  if (arr.length !== STEPS.length) {
    throw new Error(`DS-002 색 스케일이 11칸이 아니다: ${arr.length}칸 (앵커 ${hex})`);
  }
  const out = { DEFAULT: hex };
  STEPS.forEach((step, i) => {
    out[step] = arr[i];
  });
  return out;
}

/* ── 색 — DS-002 data.tokens.colors ───────────────────────────────────── */

// 브랜드 코발트. 원본 정부 디자인시스템의 파랑(#256ef4 · 관제 축과 같은 값)을 덮어쓴 자리다.
const primary = scale('#2e45dc', [
  '#eef1fe', '#e0e6fd', '#c4cffb', '#9daef7', '#6e84f1',
  '#2e45dc', '#2336b8', '#1f2e92', '#1e2b74', '#16205c', '#0f1640',
]);

// 딥 코발트(네이비) — 다크 밴드·어두운 면 전용.
// ⚠ DS-002 color_usage: 선택·활성 상태에 쓰지 않는다(활성이 전부 네이비로 보인다).
const secondary = scale('#4757a8', [
  '#eef1fe', '#dee4fa', '#bcc6f0', '#94a3e2', '#6878c8',
  '#4757a8', '#36448a', '#2a3670', '#1e2b74', '#16205c', '#0f1640',
]);

// slate 쿨톤. 관제 축의 KRDS gray(웜)와 온도가 다르다.
// 텍스트 사다리: 제목 900 / 본문 800 / 표 셀 700 / 설명 600 / 메타·표 헤더·라벨 500.
// ⚠ 400 이하는 흰 배경 위 의미 텍스트에 쓰지 않는다(4.5:1 미달) — 비활성·placeholder 전용.
const neutral = scale('#64748b', [
  '#f8fafc', '#f1f5f9', '#e2e8f0', '#cbd5e1', '#94a3b8',
  '#64748b', '#475569', '#334155', '#1e293b', '#0f172a', '#020617',
]);

// ★안내(info)는 주색과 **같은 값**이다 — 의도된 선택이며, primary 와 나란히 놓고 둘을
//   구별시키는 자리에는 쓸 수 없다(DS-002 color_usage.info.avoid_for).
const info = scale('#2e45dc', [
  '#eef1fe', '#e0e6fd', '#c4cffb', '#9daef7', '#6e84f1',
  '#2e45dc', '#2336b8', '#1f2e92', '#1e2b74', '#16205c', '#0f1640',
]);

// DS-002 semantic.**warn**. 코드 키는 관제 축과 같은 `warning` 을 유지한다(호출부 보존).
const warning = scale('#d97706', [
  '#fffbeb', '#fef3c7', '#fde68a', '#fcd34d', '#fbbf24',
  '#d97706', '#b45309', '#92400e', '#78350f', '#451a03', '#2e1102',
]);

// DS-002 semantic.**error**. 코드 키는 `danger` 유지.
const danger = scale('#dc2626', [
  '#fef2f2', '#fee2e2', '#fecaca', '#fca5a5', '#f87171',
  '#dc2626', '#b91c1c', '#991b1b', '#7f1d1d', '#450a0a', '#2c0606',
]);

// ⚠ ITEM 의 success scale 은 **600 과 700 이 같은 값(#065f46)** 이다. 오타로 보이더라도
//   고치지 않는다 — 이 파일은 투영이고 값의 정본은 상대 정의다(dont_rules).
//   앵커 #047857 은 흰 글자 배지와 흰 배경 텍스트 양쪽에서 대비를 확보한 값이라
//   밝은 emerald 로 되돌리면 배지 흰 글자가 미달한다(color_usage.success.avoid_for).
const success = scale('#047857', [
  '#ecfdf5', '#d1fae5', '#a7f3d0', '#6ee7b7', '#34d399',
  '#047857', '#065f46', '#065f46', '#064e3b', '#022c22', '#011a14',
]);

/**
 * 표 행 hover 표면 — ★**DS-002 가 규정하지 않는 자리다.**
 *
 * 관제 축은 `#FFFBEB`(호박색)를 do_rule 로 못박지만 **DS-002 에서 그 값은 경고색
 * (warn-50)** 이라 그대로 물려받으면 「경고로 읽히는 hover」가 된다. 두 축을 섞지 말라는
 * dont_rule 에 정면으로 걸린다.
 *
 * ⚠ **이 값은 ITEM 근거가 아니라 시안(SCREEN-028 `design.css:359`) 근거다** — 그 파일이
 *   *"이 시스템은 표 행 hover 표면을 규정하지 않는다. 규정되지 않은 속성만 더한다는 원칙에
 *   따라 중립 최저 단계로 얹었다"* 라고 사유와 함께 남겼다. `DS-002.known_gaps` 가 "부품
 *   카탈로그가 함께 오지 않았다"고 인정한 그 공백에 해당한다.
 *   ⇒ 상대 정의가 이 자리를 규정하면 그 값으로 교체한다.
 */
const ROW_HOVER = neutral['50'];

/**
 * 캔버스(페이지 바탕) — DS-002 `color_usage.neutral.use_for` 가 이름 붙인 **도메인 토큰**이다
 * ("표면(카드 흰색 · 빈 상태 50 · 캔버스는 도메인 토큰 #f4f6fa)"). slate 스케일 밖의 값이라
 * 별도 키로 둔다.
 */
const CANVAS = '#f4f6fa';

/* ── 타이포 — DS-002 data.typography_use(12 step) ─────────────────────── */

/**
 * DS-002 사다리 12칸. 크기·굵기·행간이 ITEM 값 그대로다.
 *
 * ★굵기 상한이 **600** 이다 — 700 이상을 쓰지 않고 위계는 크기와 색으로 만든다
 *   (`key_characteristics`). 관제 축(상한 800)과 갈리는 핵심 축이다.
 * ★본문 기준이 **15px** 이다 — 관제 축(17)보다 한 단 작다.
 * ⚠ 자간 -0.5px 는 각 칸에 되풀이하지 않는다 — 본문 뿌리에 **한 번만** 준다
 *   (`letterSpacing.body` + `PortalLayout` 뿌리). 칸마다 주면 상속과 겹쳐 두 번 적용된다.
 */
const LADDER = {
  'display-xl': ['52px', { lineHeight: '1.15', fontWeight: '600' }],
  'display-lg': ['40px', { lineHeight: '1.2', fontWeight: '600' }],
  'display-md': ['30px', { lineHeight: '1.3', fontWeight: '600' }],
  'title-lg': ['24px', { lineHeight: '1.35', fontWeight: '600' }],
  'title-md': ['20px', { lineHeight: '1.4', fontWeight: '600' }],
  'title-sm': ['17px', { lineHeight: '1.55', fontWeight: '600' }],
  'body-md': ['15px', { lineHeight: '1.6', fontWeight: '400' }],
  'body-sm': ['14px', { lineHeight: '1.55', fontWeight: '400' }],
  caption: ['13px', { lineHeight: '1.5', fontWeight: '600' }],
  button: ['15px', { lineHeight: '1.6', fontWeight: '600' }],
  label: ['14px', { lineHeight: '1.55', fontWeight: '600' }],
  'nav-link': ['15px', { lineHeight: '1.6', fontWeight: '500' }],
};

/**
 * DS-002 사다리에 **칸이 없는** 관제 축 이름들.
 *
 * 시안은 *"관제 축에 있던 display-sm(26)·body-lg(19) 는 이 시스템의 사다리에 없어 두지
 * 않는다 — 없는 칸을 남겨 두면 다음 사람이 그 자리에 관제 값을 채운다"* 고 적었다. 그 취지는
 * 따르되 **이름을 지우지는 않는다** — `text-display-sm`(6곳)·`text-body-lg`(4곳)이 공용
 * 컴포넌트에 남아 있어, 이름이 사라지면 Tailwind 가 클래스를 **오류 없이 생성하지 않아**
 * 글자 크기가 브라우저 기본값으로 조용히 떨어진다(관제 축 `secondary-700` 사고와 같은 형태).
 *
 * ⚠ **아래 두 값은 ITEM 이 직접 말한 것이 아니라 사다리 이웃 칸으로의 사상(mapping)이다.**
 *   근거: display-sm 은 display-md(30)와 title-lg(24) 사이 칸이라 아래 이웃인 title-lg 로,
 *   body-lg("큰 본문")는 DS-002 body scale 의 맨 위 칸 17px 에 본문 굵기 400 을 얹어 놓았다
 *   (`typography_use.title-sm.use_for` 가 17 을 "큰 본문"으로 부른다).
 */
const LADDER_GAP_FILL = {
  'display-sm': ['24px', { lineHeight: '1.35', fontWeight: '600' }], // ← title-lg 로 사상
  'body-lg': ['17px', { lineHeight: '1.6', fontWeight: '400' }], // ← body scale 최상단 17
};

/**
 * 관제 축이 만든 **호환 별칭**을 DS-002 사다리 칸으로 다시 건다.
 *
 * ★이 표가 없으면 `text-sm`·`text-sub`·`text-page-title` 같은 별칭이 **관제 값(17/14/22)을
 *   그대로 들고 포털 산출물에 남는다** — 이름이 같아 아무 신호도 나지 않는 채 두 축의 값이
 *   섞이는 바로 그 형태다(dont_rules 가 경고한 것).
 *
 * 사상 규칙은 관제 축이 쓴 것과 **같다** — 별칭→사다리 칸 대응은 그대로 두고, 칸의 값만
 * 채널이 정한다. 아래 주석의 `= step` 이 그 대응이다.
 *
 * ⚠ 원시 스케일(xs/sm/base/lg/xl/2xl/3xl)에는 굵기를 싣지 않는다 — `text-xs font-semibold`
 *   처럼 `font-*` 유틸과 조합되는 범용 토큰이라 굵기를 박으면 조합처마다 의도가 흔들린다
 *   (관제 축과 같은 관례).
 */
const ALIASES = {
  'page-title': LADDER['title-lg'], // = title-lg 24/600
  'section-title': LADDER['title-md'], // = title-md 20/600
  body: LADDER['body-md'], // = body-md 15/400
  sub: ['14px', { lineHeight: '1.55' }], // = body-sm 크기
  'btn-label': LADDER.button, // = button 15/600
  /**
   * ★표 헤더 — 이 채널이 관제 축과 **정면으로 갈리는 자리**다.
   *
   * DS-002 `do_rules`: "표 헤더처럼 죽여야 할 요소는 **굵기(500)보다 색(slate-400~500)으로**
   * 죽인다. 굵기를 색보다 먼저 올리지 않는다." 관제 축은 같은 14px 에 굵기 **600** 을 쓴다.
   *
   * ⚠ 색은 **500** 이다. ITEM 안에 서로 다른 두 서술이 있다 —
   *   `typography_use.label` 은 "색 slate-400", `color_usage.neutral.use_for` 는
   *   "메타·**표 헤더**·라벨 500" 이고 같은 항목의 `avoid_for` 가 "흰 배경 위 의미 텍스트에
   *   gray-400 이하(4.5:1 미달)" 를 금지한다. slate-400(#94a3b8)은 흰 면 위 2.85:1 로
   *   그 금지에 걸리므로 **500 이 ITEM 자신의 규칙과 정합**하고, 시안도 독립적으로 500
   *   (`--n-5`)을 골랐다. 이 불일치는 상대에 제기할 대상이다.
   */
  'table-header': ['14px', { lineHeight: '1.55', fontWeight: '500' }],
  /**
   * mono — DS-002 사다리 **밖**이다(ITEM 에 mono 항목이 없다). 식별자를 숫자 오독 없이
   * 읽히게 하려는 화면 사정이라, 시안이 *"caption 과 같은 크기에 맞춰 두고 굵기만 낮춘다"*
   * 로 정한 값을 따른다(SCREEN-028 `design.css`). ITEM 근거가 아니라 시안 근거다.
   */
  mono: ['13px', { lineHeight: '1.5', fontWeight: '400' }],
  xs: ['13px', { lineHeight: '1.5' }], // = caption 크기(배지·메타)
  sm: ['15px', { lineHeight: '1.6' }], // = body-md
  base: ['15px', { lineHeight: '1.6' }], // = body-md
  lg: ['20px', { lineHeight: '1.4' }], // = title-md
  xl: ['24px', { lineHeight: '1.35' }], // = title-lg
  '2xl': ['30px', { lineHeight: '1.3' }], // = display-md
  '3xl': ['40px', { lineHeight: '1.2' }], // = display-lg
};

/** DS-002 사다리가 허용하는 크기 전량(px 숫자). 가드가 이 집합으로 전수 판정한다. */
export const DS002_FONT_SIZES_PX = [13, 14, 15, 17, 20, 24, 30, 40, 52];

/** DS-002 가 허용하는 굵기 전량. **상한이 600 이다.** */
export const DS002_FONT_WEIGHTS = ['400', '500', '600'];

/* ── 모양 · 음영 · 간격 — DS-002 data.tokens ──────────────────────────── */

/**
 * 모서리 — **이름이 곧 규칙**이다(`shape_principle`).
 * 명령(버튼·칩·정보 배지)은 캡슐, 내용 상자(입력·페이저 셀·아이콘 버튼)는 8,
 * 표면(카드·표 셸·모달·빈 상태)은 16, 큰 패널·미디어는 24, 가장 조용한 사각 태그는 6.
 *
 * ⚠ 관제 축 이름(sm 4 / md 6 / lg 8 / full)은 **남긴다** — 지우면 기존 호출부의 클래스가
 *   생성되지 않아 모서리가 조용히 0 이 된다. `md`·`lg`·`full` 은 값이 우연히 DS-002 의
 *   tag·input·pill 과 같고, `sm`(4)만 DS-002 에 대응 칸이 없다(정보 부족 — 그대로 둔다).
 */
const RADIUS = {
  tag: '6px',
  input: '8px',
  tile: '12px',
  surface: '16px',
  panel: '24px',
  pill: '9999px',
};

/**
 * 음영 — ★**기본 없음이 출발점**이다(`elevation_levels` 0 · 평면).
 * 흰 표면 + slate-200 보더 1px + 라운드 16 이 기본 카드·표 셸이고, 띄우는 것이 예외다.
 *
 * ⚠ 관제 축의 sm/md/lg 는 **그대로 남긴다** — 두 체계의 단계 대응이 서지 않아 임의로
 *   매핑하면 값을 지어내는 것이 된다. 화면에서 `shadow-sm` 을 걷어내는 일(표면 평면화)은
 *   화면 축 작업이며 이 라운드 범위 밖이다.
 */
const SHADOW = {
  'card-soft':
    '0 1px 2px rgba(15,23,42,0.05), 0 10px 30px -12px rgba(30,43,116,0.16)',
  dropdown: '0 20px 25px -5px rgba(15,23,42,0.10)',
};

/**
 * 간격 명명 사다리 — `whitespace_principle` 의 "관계가 멀수록 커진다".
 * 조각 4~8 → 컴포넌트 안 10~16 → 블록 20 → 컬럼 24 → 구획 32~48 → 페이지 섹션 80.
 */
const SPACING = {
  tight: '4px',
  'label-gap': '6px',
  inline: '8px',
  dense: '10px',
  'in-component': '12px',
  'card-gap': '16px',
  block: '20px',
  column: '24px',
  section: '32px',
  group: '48px',
  'page-section': '80px',
};

/** 콘텐츠 최대폭 — `layout.max_content_width_px` 1200. 머리·본문·푸터가 같은 정렬선을 공유한다. */
const MAX_WIDTH = { wrap: '1200px' };

/** 좌우 거터 — `layout.gutter_px` 24. */
export const DS002_GUTTER_PX = 24;

/**
 * 포털 채널의 theme.extend 를 만든다.
 *
 * 관제(DS-001) extend 를 **받아서** 갈아끼운다 — 관제 표에만 있는 이름(`category-*` 등)이
 * 조용히 사라지면 그 클래스가 생성되지 않아 색이 안 붙기 때문이다. 갈아끼우는 것은
 * DS-002 가 실제로 규정한 축뿐이고, 나머지는 그대로 물려받는다(위 "투영하지 못한 것").
 *
 * @param {object} controlExtend 관제 채널(DS-001) theme.extend
 * @returns {object} 포털 채널(DS-002) theme.extend
 */
export function portalThemeExtend(controlExtend) {
  return {
    ...controlExtend,
    colors: {
      ...controlExtend.colors,
      primary,
      secondary,
      info,
      warning,
      danger,
      success,
      gray: neutral,
      neutral,
      // 관제 축과 같은 관례 — 별칭은 값을 복제하지 않고 스케일의 해당 단을 **참조**한다.
      accent: { DEFAULT: primary['400'] },
      bgLight: { DEFAULT: neutral['50'] },
      border: { DEFAULT: neutral['200'] },
      rowHover: { DEFAULT: ROW_HOVER },
      canvas: { DEFAULT: CANVAS },
    },
    // ★fontSize 는 **교체**한다(병합이 아니다) — 관제 값이 한 칸이라도 남으면 그 이름이
    //   포털 산출물에서 17px·굵기 700 을 그대로 뿜는다. 관제 표의 모든 이름을 이 표가 덮는지는
    //   회귀 가드가 키 집합 대조로 강제한다.
    fontSize: { ...LADDER, ...LADDER_GAP_FILL, ...ALIASES },
    borderRadius: { ...controlExtend.borderRadius, ...RADIUS },
    boxShadow: { ...controlExtend.boxShadow, ...SHADOW },
    spacing: { ...(controlExtend.spacing ?? {}), ...SPACING },
    maxWidth: { ...(controlExtend.maxWidth ?? {}), ...MAX_WIDTH },
    // 본문 자간 — 이 시스템은 본문 전역에 -0.5px 를 **한 번** 건다.
    letterSpacing: { ...(controlExtend.letterSpacing ?? {}), body: '-0.5px' },
    transitionDuration: { ...controlExtend.transitionDuration, base: '150ms' },
  };
}

/** 가드가 값 단위로 대조할 수 있도록 투영 원본을 그대로 내보낸다. */
export const DS002 = {
  colors: {
    primary,
    secondary,
    neutral,
    info,
    warning,
    danger,
    success,
    rowHover: ROW_HOVER,
    canvas: CANVAS,
  },
  fontSize: { ...LADDER, ...LADDER_GAP_FILL, ...ALIASES },
  ladder: LADDER,
  borderRadius: RADIUS,
  boxShadow: SHADOW,
  spacing: SPACING,
  maxWidth: MAX_WIDTH,
};
