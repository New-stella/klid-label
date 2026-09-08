import tailwindPalette from 'tailwindcss/colors';

import {
  DESIGN_CHANNEL_ENV_KEY,
  resolveDesignChannel,
  themeExtendForChannel,
} from './design-tokens/channel.js';

/**
 * 범주 구분색 8슬롯 — 진실원: LogiCraft DS-001 do_rules(범주 구분색).
 *
 * 정본 규칙: "범주 구분색(이벤트 유형·라벨 형태·역할처럼 우열 없이 서로 대등한 분류)은
 * semantic 이 선점한 대역을 피해 8슬롯 팔레트에서만 고른다. 배경은 각 색 스케일의 가장 옅은
 * 두 단계(50·100), 전경은 700 단계. 세 축이 이 한 팔레트를 공유하며 축마다 별도 색표를 만들지
 * 않는다. 슬롯 번호는 우열이나 순서를 뜻하지 않는다. 반대로 상태·성패·경고 전달에는 쓰지 않는다."
 *
 * ⚠ 값을 hex 로 옮겨 적지 않는다 — Tailwind 팔레트 객체를 **그대로 참조**한다. 그래야
 *   ①전사 오류가 원천적으로 불가능하고 ②50~950 전 스케일이 따라오며 ③구 리터럴 클래스
 *   (`bg-blue-100` 등)와 렌더 결과가 완전히 같아 이관이 색을 바꾸지 않는다.
 *   (테스트가 설정 원문에서 "hex 리터럴 0건 + 팔레트 참조"를 기계 판정한다.)
 *
 * ⚠ semantic 대역(red/rose/orange/amber/yellow/green/emerald)은 이 표에 넣지 않는다 —
 *   범주 배지가 성패·경고로 오독된다. 그 대역은 success/warning/danger 토큰이 소유한다.
 *
 * 슬롯 기준값(500단, 참고용 — 이 파일에 적지 않고 테스트가 팔레트에서 계산해 대조한다):
 *   1 blue #2B7FFF · 2 purple #AD46FF · 3 cyan #00B8DB · 4 teal #00BBA7
 *   5 indigo #615FFF · 6 pink #F6339A · 7 slate #62748E · 8 fuchsia #E12AFB
 *
 * 대비: 8슬롯 전부 700 on 100 / 700 on 50 이 WCAG AA(4.5:1) 통과 — contrastGuard 가 계산으로 단언.
 *
 * ⚠⚠ 슬롯을 **중첩 키로 쓰지 말 것**(`category: { 1: blue, 2: purple, ... }`). Tailwind v4 의
 *   JS-config 호환 계층은 중첩 색 키가 문자열 `'1'` 이면 그 가지를 통째로 버린다 — `2`~`8` 과
 *   `a1` 은 정상인데 `1` 만 사라진다(실측: `bg-category-1-100` 이 아예 생성되지 않음).
 *   클래스가 **없어도 오류가 나지 않아** 배경이 조용히 비는 방식으로 터진다(구 `secondary-700`
 *   미정의 사고와 같은 형태). 그래서 슬롯을 **`category-N` 평면 키**로 두고 spread 로 등록한다.
 */
// [@design DS-001]
const krdsCategory = {
  'category-1': tailwindPalette.blue,
  'category-2': tailwindPalette.purple,
  'category-3': tailwindPalette.cyan,
  'category-4': tailwindPalette.teal,
  'category-5': tailwindPalette.indigo,
  'category-6': tailwindPalette.pink,
  'category-7': tailwindPalette.slate,
  'category-8': tailwindPalette.fuchsia,
};

/**
 * KRDS 중립색 11단 — 진실원: LogiCraft DS-001 tokens.colors.neutral.
 * `gray` 와 `neutral` **두 이름이 이 한 표를 공유**한다(별칭). 값 표를 복제하면
 * 한쪽만 갱신되어 화면에 두 종류 회색이 섞이므로, 반드시 이 상수를 참조할 것.
 */
const krdsNeutral = {
  DEFAULT: '#464C53', // = 700 (기본 본문 회색)
  50: '#F4F5F6',
  100: '#E6E8EA',
  200: '#CDD1D5',
  300: '#B1B8BE',
  400: '#8A949E',
  500: '#6D7882',
  600: '#58616A',
  700: '#464C53',
  800: '#33363D',
  900: '#1E2124',
  950: '#131416',
};

// [@design DS-001] [@design DS-002]
/**
 * 관제 채널(DS-001 · KRDS) theme.extend — **이 객체가 관제 축 값의 정의처다.**
 *
 * ★포털 채널(DS-002)은 이것을 **바꾸지 않는다.** `design-tokens/ds002.js` 가 이 객체를 받아
 *   DS-002 가 규정한 축만 갈아끼운 새 객체를 만들고, 산출 시점에 둘 중 하나가 선택된다
 *   (`design-tokens/channel.js`). 그래서 포털 축 작업이 관제 값을 건드릴 수 없다.
 *
 * ⚠ 두 축의 **토큰 이름이 같고 값만 다르다** — 이름으로 같은 색이라고 판단하면 조용히 다른
 *   색이 뜬다(`DS-002.dont_rules`). 어느 값을 보고 있는지는 **채널**이 정한다.
 */
export const controlThemeExtend = {
      colors: {
        // KRDS(대한민국 정부 디자인시스템, LogiCraft DS-001) 색 토큰
        // ⚠ 2026-08-08: primary/info 를 DS-001 v6 정본(KRDS 공식 토큰 CSS 그대로) 값으로 교체.
        //   구 팔레트는 DS-001 known_gaps 가 "정본에 한 건도 등장하지 않는 출처 미기록
        //   값"이라 명시한 것이라 폐기한다. secondary/neutral 는 이번 교체 범위 밖(별건) —
        //   값을 건드리지 않는다.
        // ⚠ 2026-08-08(2차): 나머지 의미색 success/warning(=DS-001 warn)/danger(=DS-001 error)도
        //   같은 v6 정본 11단 스케일로 교체한다(info 와 동일 구조). **키 이름은 유지**한다 —
        //   DS-001 은 warn/error 로 명명하지만 코드 전역 호출부(warning/danger 조합 300여 곳,
        //   110개 파일)를 리네임하면 이번 변경 범위를 훨씬 넘는 대규모 치환이 되므로, 이름은
        //   보존하고 값·스케일만 정본으로 맞춘다.
        // ⚠ 2026-08-09(3차): 위 1차 주석이 "범위 밖(별건)"으로 미뤄 뒀던 **secondary/neutral**
        //   을 이번에 처리했다(그 별건이 곧 이 변경이다 — 아직 남은 일로 읽지 말 것).
        //   · neutral 을 DS-001 tokens.colors.neutral(KRDS 11단) 정본값으로 교체
        //   · 같은 값을 갖는 **gray** 를 신설 — 호출부의 `gray-*` 1,228곳은 한 줄도 고치지
        //     않고 Tailwind 기본 팔레트 폴백만 끊어 KRDS 중립색으로 자연 교체된다.
        //     (기본 팔레트를 쓰던 것이 DS-001 known_gaps 가 지적한 그 갭이다.)
        //   · secondary 를 3단(50/500/600)으로 확장
        primary: {
          DEFAULT: '#256EF4', // KRDS primary (GNB, 주요 버튼, 제목) — DS-001 tokens.colors.primary
          50: '#ECF2FE',
          100: '#D8E5FD',
          200: '#B1CEFB',
          300: '#86AFF9',
          400: '#4C87F6',
          500: '#256EF4',
          600: '#0B50D0',
          700: '#083891',
          800: '#052561',
          900: '#03163A',
          950: '#020F27',
        },
        // ⚠ 2026-08-11(4차): secondary 를 3단(50/500/600) → DS-001 tokens.colors.secondary
        //   **11단 전체**로 확장한다. 값은 정본 scale 배열을 인덱스 순서대로 옮긴 것이고
        //   기존 3단(50/500/600)의 값은 그대로다 — 확장이지 교체가 아니다.
        //   근거: UI-110 RoleBadge 의 작업자 variant 가 "secondary-05 배경 + secondary-70
        //   텍스트(10.01:1 AAA)" 를 요구하는데 `secondary-700`(#063A74) 이 정의돼 있지 않아
        //   Tailwind 가 클래스를 아예 만들지 않는다(조용히 색이 안 붙는다).
        //   ⚠ 정본에 없는 단계를 지어내지 않았다 — 11개 모두 DS-001 scale 원본값이다.
        secondary: {
          DEFAULT: '#346FB2', // KRDS secondary (강조 요소, 링크) = 500
          50: '#EEF2F7',
          100: '#D6E0EB',
          200: '#BACBDE',
          300: '#90B0D5',
          400: '#6B96C7',
          500: '#346FB2',
          600: '#1C589C',
          700: '#063A74',
          800: '#052B57',
          900: '#031F3F',
          950: '#02162C',
        },
        // accent: 기존 컴포넌트가 참조하는 별칭 유지(회귀 방지). primary-400 별칭이므로
        // primary 교체를 그대로 따라간다("= primary-400" 주석이 실제로 성립하도록).
        accent: {
          DEFAULT: '#4C87F6', // = primary-400 (포인트 아이콘, 활성 상태)
        },
        // success: DS-001 tokens.colors.semantic.success 전체 스케일 채택(info 와 동일 축 취급).
        // 이름은 정본과 동일(success)이라 그대로 유지.
        success: {
          DEFAULT: '#228738',
          50: '#EAF6EC',
          100: '#D8EEDD',
          200: '#A9DAB4',
          300: '#7EC88E',
          400: '#3FA654',
          500: '#228738',
          600: '#267337',
          700: '#285D33',
          800: '#1F4727',
          900: '#122B18',
          950: '#0E2012',
        },
        // warning: DS-001 tokens.colors.semantic.**warn** 전체 스케일 채택. 정본 명칭은 warn
        // 이지만 코드 키는 기존 호출부 보존을 위해 warning 을 유지(값·스케일만 정본 반영).
        warning: {
          DEFAULT: '#9E6A00',
          50: '#FFF3DB',
          100: '#FFE0A3',
          200: '#FFC95C',
          300: '#FFB114',
          400: '#C78500',
          500: '#9E6A00',
          600: '#8A5C00',
          700: '#614100',
          800: '#422C00',
          900: '#2E1F00',
          950: '#241800',
        },
        // danger: DS-001 tokens.colors.semantic.**error** 전체 스케일 채택. 정본 명칭은 error
        // 이지만 코드 키는 기존 호출부 보존을 위해 danger 를 유지(값·스케일만 정본 반영).
        danger: {
          DEFAULT: '#DE3412',
          50: '#FDEFEC',
          100: '#FCDFD9',
          200: '#F7AFA1',
          300: '#F48771',
          400: '#F05F42',
          500: '#DE3412',
          600: '#BD2C0F',
          700: '#8A240F',
          800: '#5C180A',
          900: '#390D05',
          950: '#260903',
        },
        // info: DS-001 tokens.colors.semantic.info 전체 스케일 채택(primary 와 동일 축 취급).
        // DEFAULT 는 5번째(index 5) 단계 — primary 스케일 구조와 대칭.
        info: {
          DEFAULT: '#0B78CB',
          50: '#E7F4FE',
          100: '#D3EBFD',
          200: '#9ED2FA',
          300: '#5FB5F7',
          400: '#2098F3',
          500: '#0B78CB',
          600: '#096AB3',
          700: '#085691',
          800: '#053961',
          900: '#03253F',
          950: '#021A2C',
        },
        // ── 중립색 ────────────────────────────────────────────────────────
        // 진실원: DS-001 tokens.colors.neutral (KRDS 11단). `gray` 와 `neutral` 은
        // **같은 값을 가리키는 두 이름**이다 — 호출부가 gray-*(1,228곳)/neutral-*(17곳)
        // 양쪽을 쓰고 있어 어느 쪽으로 써도 같은 색이 나와야 한다.
        // ⚠ 한쪽만 고치면 화면에 두 종류 회색이 섞인다. 값 표를 복제하지 말고
        //   두 키가 같은 상수(krdsNeutral)를 참조하는 구조를 유지할 것
        //   (테스트가 gray ↔ neutral 을 기계 대조한다).
        gray: krdsNeutral,
        neutral: krdsNeutral,
        // bgLight / border: 기존 컴포넌트 참조 별칭(bg-bgLight 21곳 · border-border 29곳).
        // @req R12 — 2026-08-13(4차): 위 중립색 교체 때 "범위 밖(별건)"으로 미뤄 뒀던 정렬을
        //   이번에 처리했다(그 별건이 곧 이 변경이다 — 아직 남은 일로 읽지 말 것).
        //   두 별칭은 그때까지 구 neutral 값을 들고 있어 KRDS 스케일과 어긋났고, 그래서
        //   화면에 **중립 스케일과 미세하게 다른 두 번째 회색**이 섞여 있었다.
        // ⚠ 값 표를 복제하지 말 것 — gray/neutral 과 같은 이유다. 반드시 krdsNeutral 의
        //   해당 단을 **참조**한다(테스트가 설정 원문에서 참조 여부를 기계 판정한다).
        bgLight: {
          DEFAULT: krdsNeutral[50], // 콘텐츠 배경 = 중립 50단
        },
        border: {
          DEFAULT: krdsNeutral[200], // 구분선, 테두리 = 중립 200단
        },
        // ── 범주 구분색 ────────────────────────────────────────────────────
        // 진실원: DS-001 do_rules — 값 표와 근거는 파일 상단 krdsCategory 주석 참조.
        // 사용은 `bg-category-1-100 text-category-1-700` 형태다.
        // ⚠ 값 표를 복제하지 말 것 — gray/neutral·bgLight/border 와 같은 이유로 반드시
        //   krdsCategory 상수를 **참조**한다(테스트가 설정 원문에서 참조 여부를 판정한다).
        // ⚠ 중첩(`category: {...}`)이 아니라 spread 다 — 근거는 상단 krdsCategory 주석 참조.
        // [@design DS-001]
        ...krdsCategory,
        // ── 표 행 hover 표면 ───────────────────────────────────────────────
        // 진실원: DS-001 do_rules — "표의 행 hover 표면은 #FFFBEB 를 쓴다. 긴 표에서
        // 커서가 짚은 행을 확실히 알린다. 회색 계열은 표면 배경과 겹쳐 구분이 약하다."
        // ⚠ 값을 컴포넌트에 raw hex 로 박지 말 것 — 토큰 경유가 규칙이고, 가드가
        //   src 전수 스캔으로 `#FFFBEB` 하드코딩 0건을 강제한다.
        rowHover: {
          DEFAULT: '#FFFBEB',
        },
      },
      fontFamily: {
        // 진실원: LogiCraft DS-001 tokens.typography — body/label/headline 전부
        // family "Pretendard GOV"(공공 배포판). 선언 family 명이 일반판과 다르므로
        // 1순위는 반드시 'Pretendard GOV' 여야 하고, GOV 가 로드되지 않은 환경에서
        // 시스템 폰트로 곧장 떨어지지 않도록 **2순위 'Pretendard' 폴백을 남긴다**.
        // ⚠ 한글 260자는 두 판의 아웃라인·자폭이 동일하다 — 갈리는 것은 숫자·문장부호·
        //   라틴 I W i j l w 48자뿐이라 "한글이 안 바뀐 것"은 회귀가 아니다.
        sans: [
          'Pretendard GOV',
          'Pretendard',
          '-apple-system',
          'BlinkMacSystemFont',
          '"Segoe UI"',
          'Roboto',
          '"Helvetica Neue"',
          'Arial',
          '"Apple SD Gothic Neo"',
          '"Noto Sans KR"',
          'sans-serif',
        ],
        mono: [
          'D2Coding',
          'ui-monospace',
          'SFMono-Regular',
          'Menlo',
          'Monaco',
          'Consolas',
          '"Liberation Mono"',
          '"Courier New"',
          'monospace',
        ],
      },
      // ─────────────────────────────────────────────────────────────────────
      // KRDS 타이포 (LogiCraft DS-001 KRDS Public v2 — data.tokens.typography_use)
      //
      // 진실원은 v2 에서 확정된 **15단 ladder** 다. 그 전 raw 토큰(body base 17/scale 1.125,
      // headline base 18/scale 1.2)은 ladder 를 만든 근거이고, 실제 값은 아래 표가 정본이다.
      //
      //   display-xl 45/800/1.15 · display-lg 37/800/1.2 · display-md 31/700/1.25
      //   display-sm 26/700/1.3  · title-lg   22/700/1.4 · title-md   18/600/1.45
      //   title-sm   17/600/1.5  · body-lg    19/400/1.6 · body-md    17/400/1.6
      //   body-sm    15/400/1.6  · label      14/600/1.4 · caption    14/400/1.5
      //   button     17/500/1.4  · nav-link   17/500/1.4 · mono       14/400/1.5
      //
      //   Do's : "본문 17px 이상 + line-height 1.6 이상", "본문 weight 300 이하 금지",
      //          "Pretendard 외 한글 폰트 혼용 금지"
      //
      // ⚠ 소수점 px 를 쓰지 않는다 — ladder 값이 이미 정수로 확정된 정본이다.
      //   (스케일 배수를 직접 곱한 21.6/25.9 같은 중간값은 v2 에서 22/26 으로 확정됐다.)
      //
      // ⚠ DS-001 내부 모순 — 같은 ITEM 의 design_md 문서 "Typography Hierarchy" 표는
      //   body-md 16px / body-sm 14px / label 12px 로 ladder 와 다르다. 구 구현은 그 md 표
      //   (16/14/12)를 따랐으나 **ladder(typography_use) + Do's 를 기준으로 삼는다** —
      //   그 둘은 서로 일치(17px 본문)하고 md 표 하나만 어긋나기 때문이다.
      //
      // 호출부(*.tsx 850여 곳)는 치환하지 않는다. 여기서 토큰 값만 재매핑하면
      // 기존 클래스명이 그대로 KRDS 값을 받아 전 화면에 전파된다.
      //
      // ⚠ 표준 스케일(xs/sm/base/lg/xl/2xl/3xl)에는 fontWeight 를 싣지 않는다 —
      //   `text-xs font-semibold` 처럼 font-* 유틸과 조합되는 범용 토큰이라
      //   weight 를 박으면 조합처마다 의도가 흔들린다.
      // ─────────────────────────────────────────────────────────────────────
      fontSize: {
        // ── DS-001 ladder 15단 정의. 화면이 ladder 밖 크기를 임의로 만들 이유가 없도록
        //    현재 사용처가 없는 step 도 함께 정의해 둔다.
        'display-xl': ['45px', { lineHeight: '1.15', fontWeight: '800' }], // 구 56px/w700
        'display-lg': ['37px', { lineHeight: '1.2', fontWeight: '800' }], // 구 40px/w700
        'display-md': ['31px', { lineHeight: '1.25', fontWeight: '700' }], // 구 32px
        'display-sm': ['26px', { lineHeight: '1.3', fontWeight: '700' }], // 신설
        'title-lg': ['22px', { lineHeight: '1.4', fontWeight: '700' }], // 구 24px/w600
        'title-md': ['18px', { lineHeight: '1.45', fontWeight: '600' }], // 구 20px/w500
        'title-sm': ['17px', { lineHeight: '1.5', fontWeight: '600' }], // 신설
        'body-lg': ['19px', { lineHeight: '1.6', fontWeight: '400' }], // 신설
        'body-md': ['17px', { lineHeight: '1.6', fontWeight: '400' }], // 구 16px
        'body-sm': ['15px', { lineHeight: '1.6', fontWeight: '400' }], // 구 14px
        label: ['14px', { lineHeight: '1.4', fontWeight: '600' }], // 구 12px/w500
        caption: ['14px', { lineHeight: '1.5', fontWeight: '400' }], // 신설
        button: ['17px', { lineHeight: '1.4', fontWeight: '500' }], // 신설
        'nav-link': ['17px', { lineHeight: '1.4', fontWeight: '500' }], // 신설
        // 크기 step 이다 — 글꼴 계열은 `font-mono`(D2Coding) 가 따로 지정한다.
        mono: ['14px', { lineHeight: '1.5', fontWeight: '400' }], // 신설

        // 기존 UI/UX §2.3 토큰 보존 — 소스 전반(text-sub 181회 등)에서 사용 중, 삭제 시 회귀.
        // 값만 위 ladder step 으로 재매핑한다.
        'page-title': ['22px', { lineHeight: '1.4', fontWeight: '700' }], // = title-lg (구 22px/w700, 값 동일)
        'section-title': ['18px', { lineHeight: '1.45', fontWeight: '600' }], // = title-md (구 16px)
        body: ['17px', { lineHeight: '1.6', fontWeight: '400' }], // = body-md (구 14px)
        sub: ['14px', { lineHeight: '1.6', fontWeight: '400' }], // = caption 크기 (구 12px)
        // ⚠ 버튼 텍스트는 label 계열이 아니라 **body 계열**이다 — DS `button` step.
        'btn-label': ['17px', { lineHeight: '1.4', fontWeight: '500' }], // = button (구 14px/w500)
        'table-header': ['14px', { lineHeight: '1.4', fontWeight: '600' }], // = label (구 13px/w600)

        // Tailwind 원시 스케일 재매핑 — 소스에서 본문·캡션으로 실사용 중이라
        // (text-xs 337 / text-sm 188 / text-base 9회) ladder 에 맞춘다.
        xs: ['14px', { lineHeight: '1.6' }], // 배지·캡션 = label/caption 크기 (구 12px/16px)
        sm: ['17px', { lineHeight: '1.6' }], // 본문 = body-md (구 14px/20px)
        base: ['17px', { lineHeight: '1.6' }], // = body-md (구 16px/24px)
        lg: ['18px', { lineHeight: '1.45' }], // = title-md (구 18px/28px)
        xl: ['22px', { lineHeight: '1.4' }], // = title-lg (구 20px/28px)
        '2xl': ['26px', { lineHeight: '1.3' }], // = display-sm (구 24px/32px)
        '3xl': ['31px', { lineHeight: '1.25' }], // = display-md (구 30px/36px)
      },
      borderRadius: {
        sm: '4px',
        md: '6px',
        lg: '8px',
        full: '9999px',
      },
      boxShadow: {
        sm: '0 1px 2px rgba(14,21,40,0.06)',
        md: '0 4px 10px rgba(14,21,40,0.08)',
        lg: '0 10px 24px rgba(14,21,40,0.12)',
      },
      transitionDuration: {
        100: '100ms',
        DEFAULT: '200ms',
        fast: '120ms',
        slow: '320ms',
      },
      transitionTimingFunction: {
        standard: 'cubic-bezier(0.2,0,0,1)',
        emphasized: 'cubic-bezier(0.3,0,0,1)',
      },
};

/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    screens: {
      md: '768px',
      xl: '1280px',
    },
    // ★채널이 토큰 값을 정한다 — 관제 산출물은 DS-001, 포털 산출물은 DS-002.
    //   라우트도 같은 키(`VITE_BUILD_CHANNEL`)로 이미 갈리므로 한 산출물 안에서 두 채널
    //   화면이 동시에 뜨는 형상이 없다. 그래서 산출 시점 분기 하나면 충분하다.
    extend: themeExtendForChannel(
      resolveDesignChannel(process.env[DESIGN_CHANNEL_ENV_KEY]),
      controlThemeExtend,
    ),
  },
  plugins: [],
};
