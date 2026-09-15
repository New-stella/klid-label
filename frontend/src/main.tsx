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

function mount(): void {
  ReactDOM.createRoot(rootEl as HTMLElement).render(
    <React.StrictMode>
      <AuthoringApp />
    </React.StrictMode>,
  );
}

// [@design INT-013]
// 포털 채널 산출물을 **Host 없이 단독으로 띄우는 개발 형상**의 대역 — 마운트 경로 보정과
// Host 인계 창구 대역을 함께 세운다(근거 전문은 `features/auth/devHostStub` 상단).
//
// ★ **여기(문서를 소유한 단독 진입점)에만 둔다.** Remote 진입점(`remote/AuthoringRemote.tsx`)에
//   두지 않으므로 실제 Host 안에서는 **구조적으로 활성화될 수 없다** — 조건 검사가 아니라
//   배치로 보장한다(`vite:preloadError` 를 가르는 것과 같은 관례).
//
// ★★ **`import.meta.env.DEV` 를 먼저 본다 — 산출물에서 빼는 축이 이것이다.** 산출 시점에 굳는
//    값이라 운영 빌드에서는 이 분기가 통째로 지워져 대역 모듈이 청크로 방출되지 않는다.
//    개발용 로그인 노출 값(`VITE_DEV_LOGIN_ENABLED`)은 폐쇄망 반입 산출이 **기본으로 켜서**
//    만들기 때문에 그 값만으로 가르면 반입 산출물에 인증 우회 표면이 들어간다.
//
// 대역이 토큰을 본체에 건넨 **뒤에** 렌더한다 — 먼저 렌더하면 첫 요청이 토큰 없이 나가고
// 화면도 인증 안내를 한 번 깜빡인다.
if (import.meta.env.DEV) {
  void import('./features/auth/devHostStub')
    .then(async (devHost) => {
      // 이동이 시작됐으면 문서가 곧 교체된다 — 렌더하지 않는다.
      if (devHost.applyStandaloneMountRedirect()) return;
      await devHost.seedFramedDevToken();
      devHost.installDevHostTokenHandoff();
      mount();
    })
    .catch(() => {
      // 대역을 못 세워도 앱 자체는 떠야 한다(개발 편의 장치라 화면을 막지 않는다).
      mount();
    });
} else {
  mount();
}
