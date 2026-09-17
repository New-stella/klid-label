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
 * ## ★ Host 가 넘기는 인계 창구를 «여기서» 받는다
 * Host 는 우리를 `<Remote authBridge={...} />` 로 마운트한다. 그 객체가 포털 채널에서
 * **토큰을 얻는 유일한 통로**다 — 포털 채널 창구에는 스토어 폴백이 없어서(fail-closed),
 * 받아서 등록하지 않으면 토큰이 언제나 `null` 이고 **모든 API 가 인증 없이 나간다.**
 *
 * ⚠ 실측(2026-09-10): 이 props 를 받지 않던 판에서는 포털에 정상 로그인한 상태인데도
 *   저작도구 자리에 「인증이 필요합니다 / 로그인 정보를 확인할 수 없습니다」가 떴다.
 *   Host 는 아무 잘못이 없고 우리가 넘겨받은 것을 버리고 있었다.
 *
 * ⚠ **등록을 효과(`useEffect`)로 미루지 않는다.** 미루면 첫 라우트 가드가 토큰 없이 판정해
 *   인증 안내가 한 프레임 비쳤다가 화면으로 바뀐다. 등록 함수는 같은 후보로 다시 부르면
 *   즉시 돌아오도록 **멱등**이라 렌더에서 불러도 안전하다(근거는 그 함수 주석).
 *
 * ★ **이 파일 이름과 노출 모듈 이름이 다른 것은 의도다.** MF 의 `exposes` 는 「Host 가 부르는
 *   이름 → 우리 파일 경로」 매핑이라 키와 파일명이 독립이며, 배선은
 *   `exposes: { './PortalApp': './src/remote/AuthoringRemote.tsx' }` 형태가 된다.
 *   `./PortalApp` 은 Host 가 부르는 이름이고 `AuthoringRemote` 는 우리 저장소 안에서의
 *   역할명(독립 앱 진입점 `main.tsx` 의 대비항)이다. 근거 전문은 그 상수의 주석에 있다.
 *
 * 회귀 가드: `remote/__tests__/remoteEntrypointGuard.test.tsx`
 */
import '@/styles/bootstrap';

import { AuthoringApp } from '@/AuthoringApp';
import {
  registerHostTokenHandoff,
  type TokenHandoffGateway,
} from '@/features/auth/tokenHandoff';
import { PORTAL_EMBED_ANCHOR_CLASS } from '@/lib/portalEmbedAnchor';

/**
 * Host 가 넘기는 props — **지금은 인계 창구 하나뿐**이다.
 *
 * ⚠ 이름 `authBridge` 는 Host 가 정한 계약이다(포털 인계문서 2026-09-08 §1). 바꾸면
 *   타입도 빌드도 통과한 채 **실행 시점에** 창구가 비어 전 API 가 인증 없이 나간다.
 *
 * ★ `undefined` 를 허용하는 이유는 **Host 없이 단독으로 띄우는 개발 경로**(`devHostStub`)가
 *   있기 때문이다. 그때는 그쪽이 자기 대역 창구를 등록한다.
 *
 * ⚠ 진입 payload(선택한 데이터셋 등)를 넘길 통로는 **아직 합의 전**이다. 포털이 지금은
 *   주소의 질의 문자열로만 보내고 있고, 파일 절대경로는 주소에 실을 것이 못 된다.
 *   합의되면 이 타입에 항목이 는다 — 그 전에 우리가 임의로 이름을 정하지 않는다.
 */
export interface AuthoringRemoteProps {
  authBridge?: TokenHandoffGateway;
}

export default function AuthoringRemote({ authBridge }: AuthoringRemoteProps) {
  // 렌더 중 호출이지만 모듈 변수 대입이라 멱등이고, 같은 객체면 등록 함수가 즉시 돌아온다.
  // 모양이 계약과 다르면 그쪽이 거부하고 창구를 비운 채 둔다(fail-closed) — 그 판정은
  // `tokenHandoff` 한 곳이 소유하며 여기서 다시 검사하지 않는다.
  if (authBridge) registerHostTokenHandoff(authBridge);

  // ★ 스타일 격리 앵커 — 이 클래스가 곧 「여기부터는 저작도구 영역」이다. [@design INT-013]
  //   Host 가 루트 글꼴을 62.5% 로 줄여 쓰는데, 우리 간격 유틸리티가 rem 기반이라 그대로 두면
  //   화면이 통째로 눌린다. `styles/global.css` 의 `.klid-portal-embed` 가 그 자리에서
  //   간격 기준을 절대값으로 못 박는다(근거 전문은 그 주석).
  //
  //   ⚠ Host 가 만든 요소(`.klid-authoring-slot`)에 기대지 않는다 — 상대가 이름을 바꾸면
  //     «조용히» 깨지고, 우리 시험으로는 잡히지 않는다. 우리가 소유한 요소여야 한다.
  //   ★ 2026-09-15 — 이 앵커의 쓰임이 «간격 기준 고정» 하나에서 «전역 리셋의 적용 범위»로
  //     넓어졌다. 포털 채널 산출물에서는 Tailwind Preflight 와 우리 base 선언이 전부 이
  //     클래스 하위로 좁혀져 나간다(`postcss/scope-portal-base-layer.js`). 즉 이 요소가
  //     빠지면 우리 화면이 리셋을 통째로 잃는다 — 「있으면 좋은 것」이 아니라 필수다.
  //   ⚠ `<div>` 를 하나 더 두는 것이 레이아웃을 바꾸지 않도록 `contents` 로 두지 «않는다» —
  //     `display: contents` 는 이 요소를 상자에서 지워 커스텀 프로퍼티 상속은 남기지만,
  //     Host 슬롯이 자식에 거는 레이아웃(flex/grid)이 손자로 내려가 어긋날 수 있다.
  //     높이를 그대로 물려주는 편이 안전하다.
  return (
    <div className={`${PORTAL_EMBED_ANCHOR_CLASS} h-full`}>
      <AuthoringApp />
    </div>
  );
}
