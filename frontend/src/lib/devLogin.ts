/**
 * [개발/검수 전용] dev 로그인 경로 노출 여부 단일 판정 헬퍼.
 *
 * <p>판정 규칙 (fail-closed): DEV 빌드이거나, prod 빌드라도 빌드타임 플래그
 * {@code VITE_DEV_LOGIN_ENABLED === 'true'} 인 경우에만 `/dev/login` 라우트/리다이렉트를 노출한다.
 * 그 외(미설정·임의 문자열)는 모두 비노출(false)로 떨어진다.
 *
 * <p>관제서버 미기동 폐쇄망 bring-up 용 토글. 운영에서는 환경변수 미설정이 기본이므로
 * prod 빌드 산출물에서 `/dev/login` 이 노출되지 않는다.
 */
export function isDevLoginEnabled(): boolean {
  return import.meta.env.DEV || import.meta.env.VITE_DEV_LOGIN_ENABLED === 'true';
}
