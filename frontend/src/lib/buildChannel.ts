/**
 * 빌드 채널 — 이 산출물이 어느 채널용으로 빌드됐는지의 단일 진실원.
 *
 * 배경: 저작도구 프론트엔드는 앞으로 포털 화면 안에 Module Federation Remote 로 임베드된다.
 * 포털은 자기 셸(헤더·사이드 메뉴)을 유지한 채 그 안 영역에 우리를 마운트하므로, 우리 쪽
 * 자체 셸(`PortalLayout` 의 `<header>` 등)이 그 안에서 다시 렌더되면 머리 영역이 두 벌 겹친다.
 *
 * 관제 채널과 포털 채널은 서로 다른 서버에 배포된다 — 공통 소스는 한 벌로 두고
 * "산출만" 채널별로 가른다(이 상수가 그 갈림의 단일 진입점).
 *
 * `vite-env.d.ts` 는 이 목록을 참조만 하고 사본을 두지 않는다 — 사본을 두면 두 번째
 * 진실원이 되어 한쪽만 갱신될 때 타입과 실동작이 조용히 어긋난다(`tokenIngress.ts` 의
 * `TOKEN_INGRESS_STRATEGIES` 와 같은 관례).
 *
 * ⚠ 관제 채널의 값은 `'control'` 이다 — 구 값 `'internal'` 은 폐기됐다. 그 낱말이 이
 * 저장소에서 이미 <b>인증 채널</b> 축(토큰 클레임 `channel = INTERNAL`, 관리자 부트스트랩
 * 게이트가 그 값으로 포털 사용자를 400 으로 막는다)을 가리키고 있어, 한 낱말이 두 축을
 * 가리키면 한쪽 값을 다른 쪽 판정에 쓰는 사고가 난다. 배포 향 설정(`KLID_DEPLOY_FLAVOR`)도
 * `control` 을 쓰므로 값이 그쪽과 맞춰진다.
 * ⚠⚠ 키 이름은 둘로 유지한다 — `VITE_BUILD_CHANNEL`(빌드타임에 굳는 값) 과
 * `KLID_DEPLOY_FLAVOR`(설치 시점에 정하는 값). 값만 같게 맞추고 키를 합치지 않는다.
 */
export const BUILD_CHANNELS = ['control', 'portal'] as const;

export type BuildChannel = (typeof BUILD_CHANNELS)[number];

/**
 * 기본값 — <지금 동작을 그대로 유지>한다.
 *
 * 환경변수를 주지 않은 기존 빌드(`npm run build`)는 지금처럼 산출물 하나가 두 채널을 다
 * 담당하며, 포털 채널 전용 동작(예: `PortalLayout` 자체 헤더 미노출)은 켜지지 않는다.
 * 이 저장소가 아직 Module Federation 을 도입하기 전이므로, 채널을 명시하지 않은 모든 빌드는
 * "자체 셸을 갖는 독립 앱"으로 동작해야 하고 그 기본값이 곧 `control`(관제 채널) 이다.
 */
export const DEFAULT_BUILD_CHANNEL: BuildChannel = 'control';

function readChannel(): BuildChannel {
  const v = import.meta.env.VITE_BUILD_CHANNEL as string | undefined;
  if (v && (BUILD_CHANNELS as readonly string[]).includes(v)) {
    return v as BuildChannel;
  }
  // 미설정·오타·미지의 값은 모두 기존 동작을 보존하는 기본값으로 떨어진다 (fail-closed).
  return DEFAULT_BUILD_CHANNEL;
}

/**
 * 포털 채널 빌드인지 — Module Federation Remote 로 포털 Host 셸 안에 임베드될 산출물인지 판정.
 *
 * 이 채널일 때만 우리 자체 셸(`PortalLayout` 의 `<header>` 등)을 숨긴다 — Host 가 이미
 * 자기 헤더를 갖고 있어 그대로 두면 한 화면에 머리 영역이 두 벌 겹치기 때문이다.
 *
 * `isDevLoginEnabled()`(`lib/devLogin.ts`)와 같은 이유로 값을 모듈 상수로 굳히지 않고
 * 매 호출마다 `import.meta.env` 를 다시 읽는 함수로 둔다 — 테스트에서 `vi.stubEnv` 로
 * 값을 바꿔 가며 검증할 수 있어야 한다(모듈 top-level 상수면 첫 import 시점 값에 고정된다).
 */
export function isPortalEmbedChannel(): boolean {
  return readChannel() === 'portal';
}
