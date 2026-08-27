// [@design INT-013]
/**
 * 포털 Host 가 로드할 Remote 진입점 — Module Federation `exposes` 대상.
 *
 * Host 는 `remoteEntry.js` 를 런타임에 로드해 이 모듈의 **default export** 를 자기 React
 * 트리 안에 마운트한다. 즉 우리는 문서를 소유하지 않고 **한 컴포넌트로서** 산다.
 *
 * 그래서 독립 앱 진입점(`main.tsx`)이 하는 세 가지를 여기서는 **하지 않는다**:
 *
 *   1. `ReactDOM.createRoot` / `React.StrictMode`
 *      → Host 트리 안에 두 번째 root 가 생겨 이벤트·컨텍스트가 갈린다. 마운트와 StrictMode
 *        여부는 문서를 소유한 Host 가 정한다.
 *   2. `window.addEventListener('vite:preloadError', ...)` 같은 전역 리스너
 *      → 그 핸들러는 `window.location.reload()` 를 부른다. Remote 가 달면 Host **문서
 *        전체**를 새로고침시키게 되므로 달아서는 안 된다.
 *   3. `document.getElementById('root')`
 *      → 마운트 위치는 Host 가 정한다. 우리가 DOM 을 찾아 들어가면 Host 레이아웃을 침범한다.
 *
 * 반대로 CSS 부트스트랩은 **반드시** import 한다 — Host 는 우리 폰트·전역 스타일을 모른다.
 *
 * ⚠ MF 플러그인 배선(`vite.config.ts` 의 `exposes`)은 아직 하지 않았다 — 노출 모듈 이름
 *   (`REMOTE_EXPOSED_MODULE_NAME`)이 포털팀 회신 대기 중이라 확정할 수 없다.
 *
 * 회귀 가드: `remote/__tests__/remoteEntrypointGuard.test.tsx`
 */
import '@/styles/bootstrap';

import { AuthoringApp } from '@/AuthoringApp';

export default function AuthoringRemote() {
  return <AuthoringApp />;
}
