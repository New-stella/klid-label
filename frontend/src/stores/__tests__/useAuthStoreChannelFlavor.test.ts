import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useAuthStore } from '@/stores/useAuthStore';

/**
 * [@design ADR-012] [@design INT-013] [@design AC-1103]
 *
 * 채널 판정 축이 <토큰 클레임>이 아니라 <배포 향(빌드 채널)>이라는 사양의 회귀 가드.
 *
 * 포털 Host 가 넘기는 인계 토큰에는 `channel` 클레임이 없다. 그것은 누락이 아니라 계약이고
 * (`INT-013` 「계약 경계」), 그 부재를 INTERNAL 로 떨어뜨리면 포털 산출물이 자기 화면을
 * `ChannelGuard` 로 스스로 막는다(실측 — Remote 는 정상 로드되는데 「접근 권한이 없습니다」).
 *
 * ⚠ 두 축을 <짝으로> 단언한다. 채널만 보면 「역할을 토큰에서 그대로 취하는」 변이가 살아남고,
 *   역할만 보면 「부재만 PORTAL 로 낮추는(기본값 전환)」 변이가 살아남는다.
 *
 * ⚠ 관제 향 케이스를 함께 둔 것도 그 때문이다 — 포털 케이스만 있으면 「항상 PORTAL·항상 role
 *   null」로 굳히는 변이가 전건 초록이다. 그 변이는 관제 채널을 통째로 죽인다.
 */

// helper: base64url encode (UTF-8 safe)
function b64url(obj: Record<string, unknown>): string {
  const json = JSON.stringify(obj);
  const utf8 = unescape(encodeURIComponent(json));
  return btoa(utf8).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

function buildJwt(payload: Record<string, unknown>): string {
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(payload)}.signature`;
}

const ALIVE = 9999999999;

/**
 * 포털 Host 가 실제로 넘기는 인계 토큰의 클레임 구성(실측).
 *
 * ⚠ `channel` 키를 <아예 두지 않는다>. 「없는 상태」를 `channel: undefined` 로 표현하면 그것이
 *   진짜 부재인지 명시적 undefined 인지 구분되지 않고, JSON 직렬화가 키를 지워 우연히 맞는다 —
 *   우연히 맞는 픽스처는 다음 사람이 축을 바꿀 때 조용히 어긋난다.
 * ⚠ `role` 은 <포털 어휘>다. 우리 역할 집합과 겹치는 문자열이 와도 인가 축에 쓰지 않는다.
 */
const HOST_HANDOFF_JWT = buildJwt({
  sub: '3001',
  loginId: 'portal-user',
  role: 'ADMIN',
  typ: 'access',
  iat: 1000,
  exp: ALIVE,
});

describe('decodeJwtPayload — 채널·역할 판정은 배포 향이 정한다', () => {
  beforeEach(() => {
    sessionStorage.clear();
    useAuthStore.setState({ token: null, claims: null, serverRoleStatus: 'idle' });
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    sessionStorage.clear();
  });

  describe('포털 향 — 토큰의 채널·역할을 읽지 않는다', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    });

    // ── 수용기준 1 ──────────────────────────────────────────────────
    it('★채널_클레임이_없는_Host_인계_토큰이_PORTAL_로_판정된다', () => {
      useAuthStore.getState().setToken(HOST_HANDOFF_JWT);

      const claims = useAuthStore.getState().claims;
      expect(claims).not.toBeNull();
      expect(claims?.channel).toBe('PORTAL');
      // 토큰 자체는 살아 있어야 한다 — 여기서 탈락하면 화면이 「토큰 없음」으로 오판한다.
      expect(useAuthStore.getState().token).toBe(HOST_HANDOFF_JWT);
    });

    // ── 수용기준 2 ──────────────────────────────────────────────────
    it('★채널_클레임이_INTERNAL_로_실려_와도_PORTAL_이다_기본값_전환이_아니라_강제다', () => {
      // 이 케이스가 없으면 「부재일 때만 PORTAL 로 떨어뜨리는」 절반짜리 구현이 초록으로 통과한다.
      useAuthStore
        .getState()
        .setToken(buildJwt({ sub: '3001', role: 'ADMIN', channel: 'INTERNAL', exp: ALIVE }));

      expect(useAuthStore.getState().claims?.channel).toBe('PORTAL');
    });

    it('★우리가_모르는_채널값이_실려_와도_PORTAL_이다_그_값을_아예_읽지_않는다', () => {
      // 관제 향이라면 거부되는 값이다(수용기준 6). 포털 향에서 통과하는 것이 「읽지 않는다」의 증거.
      useAuthStore.getState().setToken(buildJwt({ sub: '3001', channel: 'BOGUS', exp: ALIVE }));

      expect(useAuthStore.getState().claims?.channel).toBe('PORTAL');
    });

    // ── 수용기준 3 ──────────────────────────────────────────────────
    it('★상대_시스템의_역할이_실려_와도_인가_축에_쓰지_않는다_role_은_null_이다', () => {
      useAuthStore.getState().setToken(HOST_HANDOFF_JWT);

      expect(useAuthStore.getState().claims?.role).toBeNull();
    });

    it('★우리_역할_어휘와_겹치는_값이_와도_채워_넣지_않는다_PORTAL_USER_로_지어내지_않는다', () => {
      // 화면이 역할을 지어내면 서버 조회가 실패했을 때 있지도 않은 권한을 인정한다(fail-open).
      // 이 케이스가 없으면 `PORTAL_USER` 로 채우는 변이가 위 케이스만으로는 안 죽는다.
      useAuthStore
        .getState()
        .setToken(buildJwt({ sub: '3001', role: 'PORTAL_USER', channel: 'PORTAL', exp: ALIVE }));

      expect(useAuthStore.getState().claims?.role).toBeNull();
    });

    it('역할은_서버_조회가_채운다_비워_두는_것이_영구_무권한을_뜻하지_않는다', () => {
      // 「null 로 둔다」의 짝 — 정상 경로가 실제로 채워지는지 함께 고정한다. 이것이 없으면
      // 위 단언들이 「포털은 영영 무권한」이라는 잘못된 사양으로 읽힌다.
      useAuthStore.getState().setToken(HOST_HANDOFF_JWT);
      expect(useAuthStore.getState().claims?.role).toBeNull();

      useAuthStore.getState().setServerRole('PORTAL_USER', '포털사용자');

      expect(useAuthStore.getState().claims?.role).toBe('PORTAL_USER');
      expect(useAuthStore.getState().claims?.channel).toBe('PORTAL');
    });

    it('sub_나_exp_가_없으면_포털_향에서도_토큰을_거부한다_fail_closed_는_그대로다', () => {
      useAuthStore.getState().setToken(buildJwt({ role: 'ADMIN', exp: ALIVE }));
      expect(useAuthStore.getState().claims).toBeNull();

      useAuthStore.getState().setToken(buildJwt({ sub: '3001', role: 'ADMIN' }));
      expect(useAuthStore.getState().claims).toBeNull();
    });
  });

  describe('관제 향 — 한 톨도 바뀌지 않는다 (무변경 가드)', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
    });

    // ── 수용기준 4 ──────────────────────────────────────────────────
    it('채널_클레임이_없으면_INTERNAL_이다_관제_인계_토큰은_그_값을_싣지_않는다', () => {
      // @design ADR-063 — 이 근거는 관제 향에서 지금도 참이다. 거부하면 로그인 무한루프가 된다.
      useAuthStore.getState().setToken(buildJwt({ sub: '9001', role: 'ADMIN', exp: ALIVE }));

      expect(useAuthStore.getState().claims?.channel).toBe('INTERNAL');
    });

    // ── 수용기준 5 ──────────────────────────────────────────────────
    it('토큰_역할을_그대로_취한다', () => {
      useAuthStore.getState().setToken(buildJwt({ sub: '9001', role: 'ADMIN', exp: ALIVE }));

      expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
    });

    // ── 수용기준 6 ──────────────────────────────────────────────────
    it('모르는_채널값은_여전히_거절한다', () => {
      useAuthStore.getState().setToken(buildJwt({ sub: '9001', channel: 'BOGUS', exp: ALIVE }));

      expect(useAuthStore.getState().claims).toBeNull();
      expect(useAuthStore.getState().token).toBeNull();
    });

    it('PORTAL_이_실려_오면_그_값을_따른다_향이_토큰을_덮어쓰지_않는다', () => {
      // 관제 향에서는 판정 축이 종전대로 토큰이다 — 강제는 포털 향 한쪽에만 있다.
      useAuthStore
        .getState()
        .setToken(buildJwt({ sub: '9001', role: 'PORTAL_USER', channel: 'PORTAL', exp: ALIVE }));

      expect(useAuthStore.getState().claims?.channel).toBe('PORTAL');
      expect(useAuthStore.getState().claims?.role).toBe('PORTAL_USER');
    });

    it('향을_지정하지_않은_빌드도_관제_향과_같다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', '');

      useAuthStore.getState().setToken(buildJwt({ sub: '9001', role: 'REVIEWER', exp: ALIVE }));

      expect(useAuthStore.getState().claims?.channel).toBe('INTERNAL');
      expect(useAuthStore.getState().claims?.role).toBe('REVIEWER');
    });
  });
});
