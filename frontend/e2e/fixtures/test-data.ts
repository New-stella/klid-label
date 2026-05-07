/**
 * E2E 테스트 데이터 fixture.
 *
 * 보안: 실제 시크릿/토큰 사용 금지 — 테스트용 mock JWT만.
 * JWT 페이로드는 base64url로 디코드 가능하지만 서명 검증은 BE에서 수행하므로
 * 테스트에서는 BE를 mock하거나 별도 테스트 환경의 검증 우회 키를 사용한다.
 */

export const TEST_USERS = {
  reviewer: {
    sub: 'rev-001',
    name: '검수자김',
    role: 'REVIEWER' as const,
    channel: 'INTERNAL' as const,
  },
  worker: {
    sub: 'wkr-001',
    name: '작업자이',
    role: 'WORKER' as const,
    channel: 'INTERNAL' as const,
  },
  portalUser: {
    sub: 'ptl-001',
    name: '포털사용자',
    role: 'PORTAL_USER' as const,
    channel: 'PORTAL' as const,
  },
};

export const TEST_VIDEOS = [
  { id: 1001, title: '테스트 영상 1', eventTypeCd: 'FIRE' },
  { id: 1002, title: '테스트 영상 2', eventTypeCd: 'FALL' },
];

/**
 * 테스트용 JWT 생성 — 서명 없이 페이로드만 base64url로 인코딩.
 * BE는 테스트 환경에서 검증을 우회하거나 모의 키로 검증한다.
 */
export function makeTestJwt(claims: {
  sub: string;
  role: 'REVIEWER' | 'WORKER' | 'PORTAL_USER';
  channel: 'INTERNAL' | 'PORTAL';
  name?: string;
  expSec?: number;
}): string {
  const header = { alg: 'HS256', typ: 'JWT' };
  const payload = {
    sub: claims.sub,
    role: claims.role,
    channel: claims.channel,
    name: claims.name,
    exp: claims.expSec ?? Math.floor(Date.now() / 1000) + 3600,
  };
  const b64 = (obj: unknown) =>
    Buffer.from(JSON.stringify(obj))
      .toString('base64')
      .replace(/=/g, '')
      .replace(/\+/g, '-')
      .replace(/\//g, '_');
  return `${b64(header)}.${b64(payload)}.fake-signature-for-e2e`;
}
