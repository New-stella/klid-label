/**
 * [개발/검수 전용] dev 업로드(파일 업로드) 경로 노출 여부 단일 판정 헬퍼.
 *
 * <p>판정 규칙 (fail-closed): DEV 빌드이거나, prod 빌드라도 빌드타임 플래그
 * {@code VITE_DEV_UPLOAD_ENABLED === 'true'} 인 경우에만 `/dev/upload` 라우트/청크를 노출한다.
 * 그 외(미설정·임의 문자열)는 모두 비노출(false)로 떨어진다.
 *
 * <p>`isDevLoginEnabled`(devLogin.ts)와 동일 패턴 — dev 로그인/업로드 라우트 게이팅을 대칭화한다.
 *
 * <p>⚠ 온프렘 빌드는 이 플래그를 기본 `true` 로 주입하므로(`build-from-source.sh` ·
 * `20-build-frontend.sh` 두 곳) 운영 산출물에도 `/dev/upload` 가 포함된다 — 파일 업로드는 상시 기능이다.
 * 비노출로 되돌리려면 이 값과 backend `DEV_UPLOAD_ENABLED` 를 **함께** 꺼야 한다. 한쪽만 끄면
 * 화면과 API 중 하나만 사라져 운영자가 원인을 찾지 못한다.
 */
export function isDevUploadEnabled(): boolean {
  return import.meta.env.DEV || import.meta.env.VITE_DEV_UPLOAD_ENABLED === 'true';
}
