import type { Channel } from '@/lib/api/types';
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { resolveConfig, type RuntimeConfigKey } from '@/lib/runtimeConfig';

/**
 * `next=` 로 상위 서버에 넘기기 전에 제거할 인증 쿼리 파라미터.
 *
 * `token` 이 실제 인계 파라미터(`/ingress?token=<JWT>` — SessionIngressPage)이고,
 * 나머지 둘은 상위 시스템이 다른 이름으로 실어 보낼 경우를 대비한 방어적 제거다.
 * 셋 다 앱이 일반 쿼리로 쓰지 않아 과잉 제거 위험이 없다.
 */
const AUTH_QUERY_KEYS = ['token', 'access_token', 'jwt'] as const;

/**
 * 현재 URL 에서 인증 파라미터를 제거한 복귀 URL 을 만든다.
 *
 * 보안 (CWE-598/200): `window.location.href` 를 그대로 넘기면 `/ingress?token=<JWT>` 진입 후
 * 만료·claims 실패로 redirect 될 때 JWT 가 상위 서버 access log·프록시 로그·브라우저 히스토리에
 * 평문으로 적재된다.
 *
 * @returns 정제된 URL. 파싱 불가로 토큰 부재를 보장할 수 없으면 `null` (fail-closed — next 생략)
 */
function buildNextUrl(href: string): string | null {
  try {
    const url = new URL(href);
    AUTH_QUERY_KEYS.forEach((key) => url.searchParams.delete(key));
    return url.toString();
  } catch {
    return null;
  }
}

/**
 * 그 채널의 로그인 주소를 담는 설정 키.
 *
 * 값이 없을 때 화면이 <b>무엇을 설정해야 하는지</b> 알리기 위해 밖으로 낸다 — 이름을 화면 쪽에
 * 다시 적으면 사본이 두 번째 진실원이 되어 키를 바꿀 때 한쪽만 갱신된다.
 */
export function upstreamLoginConfigKey(channel?: Channel): RuntimeConfigKey {
  return channel === 'PORTAL' ? 'VITE_PORTAL_LOGIN_URL' : 'VITE_CONTROL_LOGIN_URL';
}

/**
 * 그 채널의 로그인 주소가 설정돼 있는지.
 *
 * 해석 순서는 `resolveConfig`(런타임 → 빌드)를 그대로 따른다 — 판정을 여기서 다시 만들면
 * "리다이렉트는 되는데 화면은 미설정이라고 말하는" 식의 어긋남이 생긴다.
 */
export function isUpstreamLoginConfigured(channel?: Channel): boolean {
  return resolveConfig(upstreamLoginConfigKey(channel)) !== undefined;
}

/**
 * 채널별 상위 시스템 로그인 URL로 redirect.
 *
 * 보안 (CWE-601 Open Redirect 방어):
 * - redirect 대상 URL은 설정에서만 사용 (사용자 입력 금지)
 * - `?next=` 파라미터로 현재 URL 보존 — 인증 파라미터 제거 후 인코딩해 전달
 * - 설정 미지정 시 redirect 안 함 (안전 가드 — 호출부가 안내 화면을 그린다)
 *
 * ★ 주소의 출처는 <b>런타임 설정 우선</b>이다(`lib/runtimeConfig`). 빌드 시점 값만 보던 구
 *   동작에서는 환경마다 다시 빌드해야 했고, 실제로 예시 주소가 구워진 산출물이 반입 대상으로
 *   놓여 있었다. 지금은 대상 서버의 `/etc/klid/frontend.env` 가 정본이다.
 *
 * @returns redirect가 실제로 발생했으면 `true`, 주소 미설정 등으로 redirect 불가 시 `false`
 */
export function redirectToUpstream(channel?: Channel): boolean {
  const target = resolveConfig(upstreamLoginConfigKey(channel));
  if (!target) return false;
  if (typeof window === 'undefined' || typeof window.location?.assign !== 'function') return false;

  // next 를 만들 수 없으면 붙이지 않고 redirect 한다 — 복귀 편의보다 토큰 미유출이 우선이고,
  // redirect 자체를 막으면 막다른 화면이 되기 때문.
  const next = buildNextUrl(window.location.href ?? '');
  const separator = target.includes('?') ? '&' : '?';
  const url = next === null ? target : `${target}${separator}next=${encodeURIComponent(next)}`;
  window.location.assign(url);
  return true;
}

/**
 * 토큰 클레임을 못 쓰는 자리에서 채널을 추론한다 — <b>산출물 채널이 1순위</b>, 경로가 폴백.
 *
 * ★ [@design INT-013] <b>포털 채널 산출물은 포털 채널만 담는다</b>(반대 향 화면이 산출물에서
 *   통째로 빠진다). 그러니 그 산출물에서의 답은 언제나 `PORTAL` 이며, 경로를 볼 것도 없다.
 *
 * ⚠ 경로만 보던 구 동작은 <b>포털 채널에서 틀린 답을 냈다</b>. 그 산출물은 Host 마운트 경로를
 *   라우터 기준 경로로 물어 실제 주소가 `/workspace/authoring/portal/...` 이다 — `/portal` 로
 *   시작하지 않으므로 판정이 `INTERNAL` 로 떨어졌고, 세션이 끊겼을 때 <b>포털 로그인 주소 대신
 *   관제 로그인 주소</b>(`VITE_CONTROL_LOGIN_URL`)를 찾아 이동에 실패했다. 되돌리지 말 것.
 *
 * 관제 채널의 동작은 그대로다 — 그 산출물에는 포털 라우트가 없어 아래 경로 폴백이 사실상
 * `INTERNAL` 을 돌려주며, 판정 결과가 한 글자도 바뀌지 않는다.
 */
export function detectChannel(): Channel {
  if (isPortalEmbedChannel()) return 'PORTAL';
  if (typeof window === 'undefined' || !window.location) return 'INTERNAL';
  const path = window.location.pathname ?? '';
  if (path.startsWith('/portal')) return 'PORTAL';
  return 'INTERNAL';
}
