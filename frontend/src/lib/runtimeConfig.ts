/**
 * 런타임 설정 — 환경마다 다른 값의 <단일 해석기>.
 *
 * <p>배경: Vite 는 `VITE_*` 를 <b>빌드 시점</b>에 정적 치환한다. 그래서 상위 시스템 로그인
 * 주소처럼 <b>환경마다 다른 값</b>이 산출물에 굳어 버렸고, 폐쇄망 반입(빌드머신이 고객 환경의
 * 실주소를 모른 채 매체를 만든다)에서는 배포 가능한 산출물 자체를 만들 수 없었다. 실제로
 * 예시 주소가 구워진 dist 가 반입 대상으로 놓여 있었다.
 *
 * <p>해결: 값의 <b>정본</b>은 대상 서버의 설정 디렉터리(`/etc/klid/frontend.env`)에 두고,
 * 설치·재생성 스크립트가 그 정본에서 브라우저가 받는 파일(`klid-config.js`)을 만든다.
 * 그 파일이 번들보다 <b>먼저</b> 로드되어 아래 전역에 값을 심고, 앱은 이 모듈을 통해 읽는다.
 * 백엔드가 DB 접속정보를 산출물이 아니라 `/etc/klid/` 에서 읽는 것과 같은 관례다.
 *
 * <p>해석 순서는 <b>런타임 → 빌드 → 없음</b>이다. 빌드 시점 값을 폴백으로 남기는 이유는
 * `npm run dev`·컨테이너 이미지처럼 생성물이 없는 경로를 깨지 않기 위해서다.
 *
 * <p>★ <b>여기 있는 값은 브라우저로 그대로 내려간다</b> — 비밀값을 넣는 자리가 아니다.
 * 그래서 내보낼 키를 {@link RUNTIME_CONFIG_KEYS} 로 <b>명시 열거(allowlist)</b> 한다.
 * 생성 스크립트도 같은 목록으로 걸러 정본에 섞인 비밀값이 나가지 않게 한다.
 * denylist 로 하면 새 비밀값이 목록에 없다는 이유로 그대로 새어 나간다.
 */

/**
 * 생성물이 값을 심는 전역 이름.
 *
 * 생성 스크립트(`deploy/onprem/scripts/install/render-frontend-config.sh`)와 <b>같은 문자열</b>
 * 이어야 한다. 그쪽은 셸이라 이 상수를 import 할 수 없으므로, 배선 가드
 * (`src/test/frontendRuntimeConfig.deploy.test.ts`)가 두 값이 같은지 고정한다.
 */
export const RUNTIME_CONFIG_GLOBAL = '__KLID_RUNTIME_CONFIG__';

/**
 * 런타임으로 주입할 수 있는 키 — <b>allowlist</b>.
 *
 * 목록에 없는 키는 생성물에 실리지 않고, 실려 들어와도 읽지 않는다. 새 값을 노출하려면
 * 사람이 이 목록에 추가해야 하며 그 행위 자체가 검토 지점이 된다.
 */
export const RUNTIME_CONFIG_KEYS = [
  /** API base — 웹 서버가 /api 를 백엔드로 프록시하므로 보통 상대경로(`/api/v1`). */
  'VITE_API_BASE_URL',
  /** 토큰 인계 채널. 값 집합의 진실원은 `features/auth/tokenIngress` 의 `IngressStrategy`. */
  'VITE_TOKEN_INGRESS',
  /** 세션 만료·401 시 이동할 관제서버 로그인 주소. 현장마다 다르다(이번 변경의 동기). */
  'VITE_CONTROL_LOGIN_URL',
  /** 같은 축의 포털 로그인 주소. */
  'VITE_PORTAL_LOGIN_URL',
  /** [개발/검수 전용] `/dev/login` 노출 토글 — 브링업 뒤 재빌드 없이 끌 수 있어야 한다. */
  'VITE_DEV_LOGIN_ENABLED',
  /** `/admin/uploads` 노출 토글 — 같은 축. */
  'VITE_DEV_UPLOAD_ENABLED',
] as const;

export type RuntimeConfigKey = (typeof RUNTIME_CONFIG_KEYS)[number];

/** 공백만 있는 값은 "미설정"으로 본다 — 생성물이 키만 남기고 값을 비운 경우. */
function normalize(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  return trimmed === '' ? undefined : trimmed;
}

/**
 * 생성물이 심은 <b>런타임 값만</b> 읽는다 (빌드 폴백 없음).
 *
 * "운영자가 이 값을 명시적으로 지정했는가"를 물어야 하는 자리에서 쓴다 — 예를 들어 개발용
 * 화면 토글은 DEV 빌드에서 기본 노출이라, 폴백까지 섞어 읽으면 운영자의 "끄기"를 구분할 수 없다.
 */
export function readRuntimeConfig(key: RuntimeConfigKey): string | undefined {
  const bag = (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL];
  // 생성물이 깨졌거나 다른 스크립트가 같은 이름을 덮어쓴 경우에도 앱이 죽지 않아야 한다.
  if (typeof bag !== 'object' || bag === null) return undefined;
  return normalize((bag as Record<string, unknown>)[key]);
}

/**
 * 값 해석의 단일 진입점 — <b>런타임 → 빌드 → 없음</b>.
 *
 * 호출부가 `import.meta.env` 를 직접 읽지 않게 하는 것이 핵심이다. 직접 읽는 곳이 하나라도
 * 남으면 그 값만 조용히 재빌드가 필요한 상태로 남는다.
 */
export function resolveConfig(key: RuntimeConfigKey): string | undefined {
  return readRuntimeConfig(key) ?? normalize(import.meta.env[key]);
}
