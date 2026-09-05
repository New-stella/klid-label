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
 */
import { isPortalEmbedChannel } from './buildChannel';

/**
 * 원격 모듈명 — Host 의 `remotes` 키가 될 값이자 MF 플러그인의 `name` 이 될 값.
 *
 * Host 는 이 이름과 노출 모듈을 이어 `authoring/PortalApp` 으로 우리를 가져간다.
 * 즉 이 값과 `REMOTE_EXPOSED_MODULE_NAME` 은 **함께** Host 의 import 지정자를 이루므로
 * 둘 중 하나만 고치면 그 지정자가 깨진다.
 */
export const REMOTE_NAME = 'authoring';

/**
 * Host 가 import 할 노출 모듈 이름 — Vite MF 플러그인의 `exposes` 키가 될 값.
 *
 * ★ **파일명(`remote/AuthoringRemote.tsx`)과 일부러 다르게 둔다 — 결함이 아니다.**
 *   MF 의 `exposes` 는 「Host 가 부르는 이름 → 우리 파일 경로」 매핑이라 **키와 파일명이
 *   독립**이다(`exposes: { './PortalApp': './src/remote/AuthoringRemote.tsx' }`).
 *   이름이 갈리는 이유도 각각 정당하다 —
 *     · `./PortalApp` 은 **Host 가 부르는 이름**이다. Host 입장에서 이 모듈은 자기 화면에
 *       끼워 넣는 「포털 앱 영역」이고, 원격 모듈명(`authoring`)과 이어져
 *       `authoring/PortalApp`(= 저작도구가 제공하는 포털 앱)으로 읽힌다.
 *     · `AuthoringRemote` 는 **우리 저장소 안에서의 역할명**이다. 우리에게 이 파일은
 *       「독립 앱 진입점(`main.tsx`)이 아닌 쪽, 즉 Remote 진입점」이며 그 대비가 이름의 전부다.
 *   파일을 개명하면 그 대비가 흐려지고, 개명이 닿는 곳도 이 파일이 아니라 진입점 스캔
 *   가드·CSS 부트스트랩 허용목록 등 **무관한 6개 파일**이라 계약값 교체와 섞을 이유가 없다.
 */
export const REMOTE_EXPOSED_MODULE_NAME = './PortalApp';

/**
 * Host 가 우리를 마운트할 경로 — 포털 채널 산출물의 라우터 basename 이 된다.
 *
 * 이 값은 **빌드타임 상수**이고 런타임 입력(쿼리스트링·Host 전달값 등)에서 오지 않는다 —
 * 런타임 주입을 허용하면 basename 이 곧 오픈 리다이렉트 표면이 된다. 같은 이유로 값의 형태도
 * "같은 출처 절대경로" 하나로 못 박는다(스킴·프로토콜 상대(`//`) 표기를 쓰지 않는다).
 */
export const PORTAL_MOUNT_BASENAME = '/workspace/authoring';

/**
 * 번들 서빙 경로 — 포털 웹서버가 우리 정적 자원을 내주는 **같은 출처의 하위 경로**.
 *
 * 빌드 산출물의 asset base 가 될 값이다. 이 값이 어긋나면 `remoteEntry.js` 는 찾아지는데
 * 그것이 참조하는 청크·폰트·이미지만 404 가 되어, **화면이 절반만 뜨는** 형태로 드러난다.
 *
 * 끝의 슬래시는 의미가 있다 — asset base 는 경로 접두어로 이어 붙여지므로 슬래시가 없으면
 * `/label-remoteassets/...` 처럼 붙는다.
 *
 * 같은 출처라 교차 출처 설정(CORS)이 필요 없다. 이것은 포털이 확정한 전제이며,
 * 저작도구 백엔드가 별도 서버로 분리되어도 바뀌지 않는다.
 */
export const REMOTE_BUNDLE_BASE_PATH = '/label-remote/';

/**
 * 진입 파일 이름 — Host 가 런타임에 내려받는 원격 진입점.
 *
 * Host 가 실제로 로드하는 주소는 `REMOTE_BUNDLE_BASE_PATH` + 이 이름이다.
 */
export const REMOTE_ENTRY_FILE_NAME = 'remoteEntry.js';

/**
 * 진입 파일에 걸 캐시 헤더 값 — **재검증을 강제**한다.
 *
 * 진입 파일만 이름에 해시가 붙지 않는다(붙으면 Host 가 주소를 알 수 없다). 그래서 이 파일은
 * 내용이 바뀌어도 **주소가 그대로**라, 캐시를 그냥 두면 브라우저가 옛 진입 파일을 계속 쓴다.
 * 그 옛 진입 파일은 이미 지워진 해시 청크를 가리키므로 **배포 직후 포털 화면이 통째로 깨진다.**
 *
 * `no-cache` 는 "캐시하지 않는다"가 아니라 "**쓰기 전에 서버에 물어본다**"는 뜻이다 —
 * 바뀌지 않았으면 304 로 끝나므로 `no-store` 보다 싸고, 목적(최신성 보장)에는 충분하다.
 * ⚠ 이 헤더를 거는 주체는 **포털 웹서버**이고 우리 번들이 아니다. 이 상수는 그 설정과
 *   맞춰야 할 값을 기록해 둔 것이다.
 */
export const REMOTE_ENTRY_CACHE_CONTROL = 'no-cache';

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
