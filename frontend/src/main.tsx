import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';

// 폰트 자가호스팅(self-host) — npm 패키지의 로컬 woff2 만 사용, 런타임 폰트 CDN 요청 0.
// 두 CSS 모두 @font-face src 가 상대경로 woff2 이며 Vite 가 해시 에셋으로 번들한다.
// dynamic-subset: unicode-range 로 필요한 서브셋만 로드(font-display:swap 내장).
//
// Pretendard **GOV**(공공 배포판) — 선언 family 명은 'Pretendard GOV' 로 일반판과 다르다.
// 한글 260자는 일반판과 아웃라인·자폭까지 동일하고, 실제로 갈리는 것은 숫자 0-9·문장부호·
// 라틴 I W i j l w 48자다(I/l/1 혼동을 줄인 판). 표에 빽빽한 영상 ID·촬영일시의 판독성이
// 이 교체의 실익이며, **한글이 그대로인 것은 회귀가 아니라 정상**이다.
import 'pretendard-gov/dist/web/static/pretendard-gov-dynamic-subset.css';
import 'd2coding/d2coding-subset.css';

import { App } from './App';
import { ToastProvider } from './components/common/ToastProvider';
import { queryClient } from './lib/queryClient';
import './styles/global.css';

// 전역 동적 import 실패 가드 — 주로 프로덕션 modulepreload 용이지만 재배포로 stale 해진
// 청크 대비 belt-and-suspenders. (dev 의 1차 방어는 라우터의 lazyWithRetry.)
// 세션당 1회만 reload 하여 무한 새로고침 루프를 차단한다.
window.addEventListener('vite:preloadError', (event) => {
  event.preventDefault();
  const KEY = 'klid-vite-preload-reloaded';
  try {
    if (!sessionStorage.getItem(KEY)) {
      sessionStorage.setItem(KEY, '1');
      window.location.reload();
    }
  } catch {
    // sessionStorage 접근 불가(프라이빗 모드 등) 시 reload 시도하지 않음.
  }
});

const rootEl = document.getElementById('root');
if (!rootEl) throw new Error('#root element not found');

ReactDOM.createRoot(rootEl).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <App />
      </ToastProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
