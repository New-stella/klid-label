import type { Preview } from '@storybook/react-vite';

// 앱 진입점과 같은 전역 스타일(폰트 · 기본 리셋). 포털 토큰 · 부품 스킨은 포털 틀이 불러온다.
import '@/styles/bootstrap';

const preview: Preview = {
  parameters: {
    // 화면 전체 — 포털 카드 안 iframe 과 같은 조건으로 선다
    layout: 'fullscreen',
  },
};

export default preview;
