/**
 * [개발/검수 전용] dev 로그인 경로 노출 여부 단일 판정 헬퍼.
 *
 * <p>판정 규칙 (fail-closed): DEV 빌드이거나, prod 빌드라도 빌드타임 플래그
 * {@code VITE_DEV_LOGIN_ENABLED === 'true'} 인 경우에만 `/dev/login` 라우트/리다이렉트를 노출한다.
 * 그 외(미설정·임의 문자열)는 모두 비노출(false)로 떨어진다.
 *
 * <p>관제서버 미기동 폐쇄망 bring-up 용 토글 — 그때만 임시로 켠다.
 * ⚠ 온프렘 빌드는 이 플래그를 기본 `true` 로 주입하므로(`build-from-source.sh` ·
 * `20-build-frontend.sh` 두 곳) `/dev/login` 라우트는 운영 산출물에도 포함된다.
 * 다만 실제 게이팅은 backend `authoring.dev.login.enabled` 이고 운영은 기본 OFF 라
 * `/v1/dev/tokens` 는 404 다. 그 토글은 켜는 것 자체가 막혀 있다 — `DevToggleProfileGuard` 가
 * prd·stg 에서 부팅을 거부한다(인증 우회 표면 미개방).
 * ⚠ 업로드 축(`devUpload.ts`)은 운영 상시 기능이 됐지만 **이쪽은 아니다** — 결론을 옮겨 적지 말 것.
 */
export function isDevLoginEnabled(): boolean {
  return import.meta.env.DEV || import.meta.env.VITE_DEV_LOGIN_ENABLED === 'true';
}
