import React from 'react';
import ReactDOM from 'react-dom/client';

// 폰트·전역 스타일 — 로드 지점은 `styles/bootstrap` 한 곳이며 Remote 진입점도 같은 것을 쓴다
// (두 진입점에 import 를 복제하면 한쪽만 갱신될 때 채널별로 스타일이 갈린다).
import './styles/bootstrap';

import { AuthoringApp } from './AuthoringApp';

// 전역 동적 import 실패 가드 — 주로 프로덕션 modulepreload 용이지만 재배포로 stale 해진
// 청크 대비 belt-and-suspenders. (dev 의 1차 방어는 라우터의 lazyWithRetry.)
// 세션당 1회만 reload 하여 무한 새로고침 루프를 차단한다.
//
// ⚠ 이것은 **문서를 소유한 진입점만** 달 수 있다 — 핸들러가 문서 전체를 새로고침하므로
//   포털 Host 안에 실리는 Remote 진입점(`remote/AuthoringRemote.tsx`)은 달지 않는다.
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
    <AuthoringApp />
  </React.StrictMode>,
);
