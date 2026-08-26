// [@design INT-013]
/**
 * 포털 Module Federation 임베딩 계약 — Host 와 맞춰야 하는 값의 단일 지점.
 *
 * 저작도구 FE 는 포털 Host 안에 **Remote** 로 실린다. Host 가 `remoteEntry.js` 를 런타임에
 * 로드해 자기 React 트리 안(같은 DOM 문서)에 우리 화면을 마운트한다.
 *
 * 이 모듈이 존재하는 이유는 하나다 — **협의 회신이 오면 여기 값만 바꾸면 되게** 하기 위해서다.
 * 같은 값을 라우터·빌드 설정·문서에 복제하면 한쪽만 갱신될 때 Host 가 우리를 못 찾거나
 * (모듈명 불일치) 전 화면이 404 가 된다(마운트 경로 불일치).
 */
import { isPortalEmbedChannel } from './buildChannel';

/**
 * Host 가 import 할 노출 모듈 이름 — Vite MF 플러그인의 `exposes` 키가 될 값.
 *
 * ⚠ **잠정값이다.** 포털팀 회신 대기 중이며, 확정되면 이 상수만 고친다.
 * ⚠ MF 플러그인 배선(`vite.config.ts` 의 `exposes`)은 아직 하지 않았다 — 이름이 확정되지
 *   않은 채로 배선하면 잘못된 이름이 산출물에 굳어 Host 가 로드에 실패한다.
 */
export const REMOTE_EXPOSED_MODULE_NAME = './AuthoringApp';

/**
 * Host 가 우리를 마운트할 경로 — 포털 채널 산출물의 라우터 basename 이 된다.
 *
 * ⚠ **잠정값이다.** 경로는 협의 중이며 확정되면 이 상수만 고친다.
 *
 * 이 값은 **빌드타임 상수**이고 런타임 입력(쿼리스트링·Host 전달값 등)에서 오지 않는다 —
 * 런타임 주입을 허용하면 basename 이 곧 오픈 리다이렉트 표면이 된다. 같은 이유로 값의 형태도
 * "같은 출처 절대경로" 하나로 못 박는다(스킴·프로토콜 상대(`//`) 표기를 쓰지 않는다).
 */
export const PORTAL_MOUNT_BASENAME = '/workspace/authoring';

/**
 * 이 산출물이 써야 할 라우터 basename — 포털 채널이면 마운트 경로, 아니면 없음.
 *
 * 판정은 `isPortalEmbedChannel()` 을 **재사용**한다. `import.meta.env` 를 여기서 다시 읽으면
 * 채널 판정이 두 벌이 되어(오타 처리·기본값 정책 포함) 한쪽만 갱신될 때 조용히 갈린다.
 *
 * ⚠ 내부(관제) 채널에서 `undefined` 가 아닌 값이 나가면 **전 라우트가 하위 경로로 밀려
 *   모든 화면이 404** 가 된다. 회귀 가드는 `lib/__tests__/remoteMount.test.ts` 와
 *   `router/__tests__/portalMountBasename.test.tsx`.
 */
export function resolveRouterBasename(): string | undefined {
  return isPortalEmbedChannel() ? PORTAL_MOUNT_BASENAME : undefined;
}
