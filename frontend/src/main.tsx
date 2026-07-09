import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';

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
