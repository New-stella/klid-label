// [@design INT-013]
/**
 * 포털 Module Federation 임베딩 계약 — Host 와 맞춰야 하는 값의 단일 지점.
 *
 * 저작도구 FE 는 포털 Host 안에 **Remote** 로 실린다. Host 가 `remoteEntry.js` 를 런타임에
 * 로드해 자기 React 트리 안(같은 DOM 문서)에 우리 화면을 마운트한다.
 *
 * 이 모듈이 존재하는 이유는 하나다 — **계약값을 한 곳에만 두기** 위해서다. 같은 값을
 * 라우터·빌드 설정·웹서버 설정·문서에 복제하면 한쪽만 갱신될 때 Host 가 우리를 못 찾거나
 * (모듈명·서빙 경로 불일치) 전 화면이 404 가 된다(마운트 경로 불일치).
 *
 * ## 값의 출처 — 포털이 정했고 저작도구가 맞춘다
 * 아래 값은 **포털팀 회신(2026-08-26)으로 확정**됐다. 정본은 설계 `INT-013` 의
 * 「진입점·마운트·서빙 규약」 절이며, 이 파일은 그것을 코드로 옮긴 사본이다.
 * ⚠ **우리가 임의로 바꿀 수 있는 값이 아니다.** 이름이 어긋나면 실행 시점에 Host 가
 *   원격 모듈을 찾지 못하고, 그 실패는 빌드가 아니라 **런타임에** 드러난다.
 *
 * ⚠ MF 플러그인 배선(`vite.config.ts` 의 `exposes`)과 웹서버 캐시 헤더 설정은 아직 하지
 *   않았다. 이 상수들은 그 배선이 참조할 자리이며, 지금은 **값만 굳혀 둔 상태**다.
 *
 * ★ 계약 **문자열 상수는 `./remoteMountContract` 가 소유**한다 — 빌드 설정(`vite.config.ts`)이
 *   Node 에서 같은 값을 읽어야 하는데 이 파일은 `./buildChannel` 을 통해 `import.meta.env` 에
 *   닿아 Node 에서 평가될 수 없기 때문이다. 사유 전문은 그 파일 헤더에 있다.
 *   여기서는 그대로 **재수출**하므로 `@/lib/remoteMount` 에서 가져오던 코드는 변경이 없다.
 */
import { isPortalEmbedChannel } from './buildChannel';
import { PORTAL_MOUNT_BASENAME } from './remoteMountContract';

export {
  REMOTE_NAME,
  REMOTE_EXPOSED_MODULE_NAME,
  PORTAL_MOUNT_BASENAME,
  REMOTE_BUNDLE_BASE_PATH,
  REMOTE_ENTRY_FILE_NAME,
  REMOTE_ENTRY_CACHE_CONTROL,
} from './remoteMountContract';

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
  if (isPortalEmbedChannel()) return PORTAL_MOUNT_BASENAME;

  // ★ 관제 채널은 <자산 base 에서 도출>한다 (2026-09-04).
  //   현장은 https://www.aicctv.go.kr/label-studio/ 에서 열린다. basename 이 없으면
  //   첫 진입은 되는데 링크·새로고침이 그 접두어를 잃어 404 가 된다 —
  //   그리고 그 실패는 <빌드가 아니라 브라우저에서> 드러난다.
  //
  //   값의 출처를 Vite 의 base(import.meta.env.BASE_URL)로 <하나로> 둔 이유:
  //   자산 경로와 라우팅 접두어는 항상 같아야 하는데, 따로 받으면 한쪽만 바뀌어
  //   "화면은 뜨는데 링크가 깨지는" 형태로 갈린다.
  //   ⚠ 런타임 입력에서 받지 않는다 — basename 을 런타임으로 열면 오픈 리다이렉트 표면이 된다.
  const base = import.meta.env.BASE_URL;
  if (typeof base !== 'string') return undefined;
  const trimmed = base.replace(/\/+$/, '');
  return trimmed === '' ? undefined : trimmed;
}
