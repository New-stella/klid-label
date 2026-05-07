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
        // UI/UX FINAL §2.2 — 9색 디자인 토큰
        primary: {
          DEFAULT: '#1F4E79', // GNB, 주요 버튼, 제목
        },
        secondary: {
          DEFAULT: '#2E75B6', // 강조 요소, 링크
        },
        accent: {
          DEFAULT: '#2196F3', // 포인트 아이콘, 활성 상태
        },
        success: {
          DEFAULT: '#4CAF50', // 완료, 승인
        },
        warning: {
          DEFAULT: '#FF9800', // 검토 중, 주의
        },
        danger: {
          DEFAULT: '#F44336', // 반려, 오류
        },
        neutral: {
          DEFAULT: '#757575', // 보조 텍스트, 비활성
        },
        bgLight: {
          DEFAULT: '#F5F7FA', // 콘텐츠 배경
        },
        border: {
          DEFAULT: '#E0E0E0', // 구분선, 테두리
        },
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
