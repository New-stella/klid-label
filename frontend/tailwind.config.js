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
        // UI/UX FINAL §2.2 — 9색 디자인 토큰 (mock modern blue tone)
        primary: {
          DEFAULT: '#2563EB', // mock primary-600 (GNB, 주요 버튼, 제목)
          50: '#EFF6FF',
          100: '#DBEAFE',
          200: '#BFDBFE',
          300: '#93C5FD',
          400: '#60A5FA',
          500: '#3B82F6',
          600: '#2563EB',
          700: '#1D4ED8',
          800: '#1E40AF',
          900: '#1E3A8A',
        },
        secondary: {
          DEFAULT: '#3B82F6', // mock primary-500 (강조 요소, 링크)
        },
        accent: {
          DEFAULT: '#60A5FA', // mock primary-400 (포인트 아이콘, 활성 상태)
        },
        success: {
          DEFAULT: '#10B981', // mock green-500 (완료, 승인)
        },
        warning: {
          DEFAULT: '#F59E0B', // mock yellow-500 (검토 중, 주의)
        },
        danger: {
          DEFAULT: '#EF4444', // mock red-500 (반려, 오류)
        },
        neutral: {
          DEFAULT: '#6B7280', // mock gray-500 (보조 텍스트, 비활성)
        },
        bgLight: {
          DEFAULT: '#F9FAFB', // mock gray-50 (콘텐츠 배경)
        },
        border: {
          DEFAULT: '#E5E7EB', // mock gray-200 (구분선, 테두리)
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
          'sans-serif',
        ],
      },
      fontSize: {
        // UI/UX §2.3
        'page-title': ['22px', { fontWeight: '700' }],
        'section-title': ['16px', { fontWeight: '600' }],
        body: ['14px', { fontWeight: '400' }],
        sub: ['12px', { fontWeight: '400' }],
        'btn-label': ['14px', { fontWeight: '500' }],
        'table-header': ['13px', { fontWeight: '600' }],
      },
      transitionDuration: {
        100: '100ms',
      },
    },
  },
  plugins: [],
};
