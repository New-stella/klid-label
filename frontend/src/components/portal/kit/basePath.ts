/**
 * 배포 base path 헬퍼. 프로덕션(/portal/)과 로컬 dev(/) 를 자동 전환한다.
 * KRDS 컴포넌트 등 <a href> 를 직접 렌더하는 곳에서 사용.
 * React Router <Link to> 는 basename 이 자동 처리하므로 불필요.
 *
 * 적용 지점은 "링크를 실제로 렌더하는 곳" 이다 (2026-08-18 확정):
 * 커스텀 컴포넌트(MoreLink·StatCard·ResultCard 등)는 컴포넌트 안에서 한 번만 붙이고,
 * 호출부는 앱 경로(/datasets/1)를 그대로 넘긴다. 킷 컴포넌트(Button as="a"·Breadcrumb·
 * Header)는 안을 못 고치므로 호출부에서 감싼다.
 */

/** 후행 슬래시 제거한 base path (dev: '', prod: '/portal') */
const base = import.meta.env.BASE_URL.replace(/\/$/, '')

/**
 * 내부 경로에 base path 를 붙인다. 외부(http 등)·앵커(#)는 그대로 반환.
 * 이미 base 로 시작하는 경로는 다시 붙이지 않는다 (렌더 지점과 호출부가
 * 겹쳐 /portal/portal 이 되는 사고를 막는다)
 */
export function href(path: string): string {
  if (!path.startsWith('/')) return path
  if (base && (path === base || path.startsWith(`${base}/`))) return path
  return base + path
}

/**
 * public/ 자산 경로에 base path 를 붙인다. 번들러를 거치지 않는 자산이라
 * import 한 자산(assets/)과 달리 vite 가 자동으로 고쳐 주지 않는다.
 */
export const asset = href
