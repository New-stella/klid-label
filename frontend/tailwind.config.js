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
        // KRDS(대한민국 정부 디자인시스템, LogiCraft DS-001) 색 토큰 — 구 mock blue(#2563EB) 교체
        primary: {
          DEFAULT: '#0F4C97', // KRDS primary (GNB, 주요 버튼, 제목)
          50: '#EEF4FC',
          100: '#DBE7FA',
          200: '#B5D0F8',
          300: '#86B5F2',
          400: '#5D97E8',
          500: '#3A7BD5',
          600: '#1A5FBF',
          700: '#0F4C97',
          800: '#0A3470',
          900: '#06214E',
          950: '#031026',
        },
        secondary: {
          DEFAULT: '#1850D7', // KRDS secondary (강조 요소, 링크)
        },
        // accent: 기존 컴포넌트가 참조하는 별칭 유지(회귀 방지), 값만 KRDS 톤으로
        accent: {
          DEFAULT: '#5D97E8', // = primary-400 (포인트 아이콘, 활성 상태)
        },
        success: '#117C44', // KRDS success (완료, 승인)
        warning: '#C25700', // KRDS warning (검토 중, 주의)
        danger: '#D1322C', // KRDS danger (반려, 오류)
        info: '#0F4C97', // KRDS info
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
      fontSize: {
        // KRDS 타이포 스케일 [size, {lineHeight, fontWeight}]
        'display-xl': ['56px', { lineHeight: '1.05', fontWeight: '700' }],
        'display-lg': ['40px', { lineHeight: '1.1', fontWeight: '700' }],
        'display-md': ['32px', { lineHeight: '1.15', fontWeight: '700' }],
        'title-lg': ['24px', { lineHeight: '1.3', fontWeight: '600' }],
        'title-md': ['20px', { lineHeight: '1.4', fontWeight: '500' }],
        'body-md': ['16px', { lineHeight: '1.5' }],
        'body-sm': ['14px', { lineHeight: '1.5' }],
        label: ['12px', { lineHeight: '1.3', fontWeight: '500' }],
        // 기존 UI/UX §2.3 토큰 보존 — 소스 전반(text-sub 130+회 등)에서 사용 중, 삭제 시 회귀
        'page-title': ['22px', { fontWeight: '700' }],
        'section-title': ['16px', { fontWeight: '600' }],
        body: ['14px', { fontWeight: '400' }],
        sub: ['12px', { fontWeight: '400' }],
        'btn-label': ['14px', { fontWeight: '500' }],
        'table-header': ['13px', { fontWeight: '600' }],
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
