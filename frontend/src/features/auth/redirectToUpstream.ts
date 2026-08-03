import type { Channel } from '@/lib/api/types';

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
 * 채널별 상위 시스템 로그인 URL로 redirect.
 *
 * 보안 (CWE-601 Open Redirect 방어):
 * - redirect 대상 URL은 환경변수에서만 사용 (사용자 입력 금지)
 * - `?next=` 파라미터로 현재 URL 보존 — 인증 파라미터 제거 후 인코딩해 전달
 * - 환경변수 미설정 시 redirect 안 함 (안전 가드)
 *
 * @returns redirect가 실제로 발생했으면 `true`, 환경변수 미설정 등으로 redirect 불가 시 `false`
 */
export function redirectToUpstream(channel?: Channel): boolean {
  const portalUrl = import.meta.env.VITE_PORTAL_LOGIN_URL as string | undefined;
  const controlUrl = import.meta.env.VITE_CONTROL_LOGIN_URL as string | undefined;
  const target = channel === 'PORTAL' ? portalUrl : controlUrl;
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
 * URL 경로 또는 환경에서 채널을 추론한다.
 * - `/portal/*` 진입은 PORTAL
 * - 그 외엔 INTERNAL (기본)
 */
export function detectChannel(): Channel {
  if (typeof window === 'undefined' || !window.location) return 'INTERNAL';
  const path = window.location.pathname ?? '';
  if (path.startsWith('/portal')) return 'PORTAL';
  return 'INTERNAL';
}
