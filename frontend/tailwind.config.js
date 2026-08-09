/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    screens: {
      md: '768px',
      xl: '1280px',
    },
    extend: {
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
        secondary: {
          DEFAULT: '#1850D7', // KRDS secondary (강조 요소, 링크) — 이번 교체 범위 밖, 값 유지
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
        neutral: {
          DEFAULT: '#3F4956', // KRDS neutral (보조 텍스트, 비활성)
          50: '#FAFBFC',
          100: '#F2F4F6',
          200: '#E1E5EA',
          300: '#C5CCD4',
          400: '#A3ABB6',
          500: '#7B8693',
          600: '#5A6573',
          700: '#3F4956',
          800: '#2E3641',
          900: '#1E252D',
          950: '#0E141B',
        },
        // bgLight / border: 기존 컴포넌트 참조 별칭 유지(회귀 방지), 값만 KRDS neutral 정렬
        bgLight: {
          DEFAULT: '#FAFBFC', // = neutral-50 (콘텐츠 배경)
        },
        border: {
          DEFAULT: '#E1E5EA', // = neutral-200 (구분선, 테두리)
        },
      },
      fontFamily: {
        sans: [
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
    },
  },
  plugins: [],
};
