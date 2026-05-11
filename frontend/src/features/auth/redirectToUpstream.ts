import type { Channel } from '@/lib/api/types';

/**
 * 채널별 상위 시스템 로그인 URL로 redirect.
 *
 * 보안 (CWE-601 Open Redirect 방어):
 * - redirect 대상 URL은 환경변수에서만 사용 (사용자 입력 금지)
 * - `?next=` 파라미터로 현재 URL 보존 — 인코딩 후 전달
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

  const currentHref = window.location.href ?? '';
  const next = encodeURIComponent(currentHref);
  const separator = target.includes('?') ? '&' : '?';
  const url = `${target}${separator}next=${next}`;
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
