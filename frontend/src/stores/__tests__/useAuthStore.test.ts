import { beforeEach, describe, expect, it } from 'vitest';

import { useAuthStore } from '@/stores/useAuthStore';

// helper: base64url encode for JWT payload (UTF-8 safe)
function b64url(obj: Record<string, unknown>): string {
  const json = JSON.stringify(obj);
  const utf8 = unescape(encodeURIComponent(json));
  const b64 = btoa(utf8);
  return b64.replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

describe('useAuthStore', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  it('useAuthStore_토큰_저장_후_clear시_null', () => {
    useAuthStore.setState({ token: 'tok', claims: null });
    expect(useAuthStore.getState().token).toBe('tok');

    useAuthStore.getState().clear();
    expect(useAuthStore.getState().token).toBeNull();
    expect(useAuthStore.getState().claims).toBeNull();
  });

  it('setToken시_JWT_payload_decode되어_claims_저장', () => {
    const payload = {
      sub: 'user-123',
      role: 'REVIEWER',
      channel: 'INTERNAL',
      exp: 9999999999,
      name: '박찬기',
    };
    const token = `header.${b64url(payload)}.sig`;

    useAuthStore.getState().setToken(token);

    const claims = useAuthStore.getState().claims;
    expect(claims).not.toBeNull();
    expect(claims?.sub).toBe('user-123');
    expect(claims?.role).toBe('REVIEWER');
    expect(claims?.channel).toBe('INTERNAL');
    expect(claims?.name).toBe('박찬기');
  });

  it('setToken시_잘못된_JWT는_token도_저장되지_않음', () => {
    // 보안: invalid JWT 는 token/claims 둘 다 저장하지 않는다 (fail-secure).
    useAuthStore.getState().setToken('not-a-jwt');
    expect(useAuthStore.getState().token).toBeNull();
    expect(useAuthStore.getState().claims).toBeNull();
  });

  it('관리자_토큰으로_로그인이_된다', () => {
    // ★역할 목록에 관리자가 없던 동안 이 토큰은 클레임 객체째 버려져, 관리자가 **로그인 자체를
    //   못 했다**(버튼이 안 보이는 것이 아니라 진입이 막혔다). 그 회귀를 고정한다.
    const token = `header.${b64url({
      sub: '9001',
      role: 'ADMIN',
      channel: 'INTERNAL',
      exp: 9999999999,
      name: '관리자',
    })}.sig`;

    useAuthStore.getState().setToken(token);

    const claims = useAuthStore.getState().claims;
    expect(claims).not.toBeNull();
    expect(claims?.role).toBe('ADMIN');
    expect(useAuthStore.getState().token).toBe(token);
  });

  it('모르는_역할_값이_와도_로그인이_통째로_깨지지_않는다', () => {
    // ★서버가 역할을 새로 늘리는 것은 정상적인 일이다. 그때마다 화면이 진입 불가가 되는 것은
    //   fail-closed 가 아니라 고장이다 — 관리자 역할이 늘었을 때 실제로 그 일이 일어났다.
    //   인증은 살리고 **역할만 미부여로 낮춘다**(권한은 하나도 주지 않는다).
    const token = `header.${b64url({
      sub: 'u-1',
      role: 'SOMETHING_NEW',
      channel: 'INTERNAL',
      exp: 9999999999,
      name: '홍길동',
    })}.sig`;

    useAuthStore.getState().setToken(token);

    const claims = useAuthStore.getState().claims;
    expect(claims).not.toBeNull();
    expect(claims?.sub).toBe('u-1');
    expect(claims?.channel).toBe('INTERNAL');
    // 모르는 값에 권한을 주지 않는다 — 원문을 그대로 역할로 인정하면 그게 인가 결함이다.
    expect(claims?.role).toBeNull();
  });

  it('역할_클레임이_비어_있으면_미부여로_읽는다', () => {
    // Phase 2 — 역할 부여 대기 상태의 인증 토큰. 위 「모르는 값」과 결과는 같지만 원인이 달라
    // 두 케이스를 함께 둔다(한쪽만 두면 다른 쪽 분기가 사라져도 초록이다).
    const token = `header.${b64url({
      sub: 'u-2',
      channel: 'INTERNAL',
      exp: 9999999999,
    })}.sig`;

    useAuthStore.getState().setToken(token);

    expect(useAuthStore.getState().claims).not.toBeNull();
    expect(useAuthStore.getState().claims?.role).toBeNull();
  });

  it('채널이_없는_토큰은_INTERNAL_로_수용한다', () => {
    // @design ADR-063 — 관제 인계 토큰은 channel 클레임을 싣지 않는다. BE 가 부재를 INTERNAL 로
    //   기본 처리하므로 FE 도 동일하게 수용한다. 거부하면 관제 토큰이 디코드에서 탈락해
    //   claims=null → 로그인 무한루프가 된다(실측 2026-09-05).
    const token = `header.${b64url({ sub: 'u-3', role: 'ADMIN', exp: 9999999999 })}.sig`;

    useAuthStore.getState().setToken(token);

    expect(useAuthStore.getState().claims?.channel).toBe('INTERNAL');
    expect(useAuthStore.getState().token).toBe(token);
  });

  it('채널값이_있는데_모르는_값이면_여전히_거절한다', () => {
    // 부재는 INTERNAL 로 낮추지만, 값이 <있는데> 우리가 모르는 채널이면 무효다(BE valueOf 대칭).
    //   이 대조가 완화가 어디까지인지를 고정한다.
    const token = `header.${b64url({ sub: 'u-3', channel: 'BOGUS', exp: 9999999999 })}.sig`;

    useAuthStore.getState().setToken(token);

    expect(useAuthStore.getState().claims).toBeNull();
    expect(useAuthStore.getState().token).toBeNull();
  });

  it('setServerRole_은_토큰을_유지한_채_claims_role_만_갱신한다', () => {
    const token = `h.${b64url({ sub: 'u1', channel: 'INTERNAL', exp: 9999999999 })}.s`;
    useAuthStore.getState().setToken(token);
    // 관제 토큰: role 클레임이 없어 decode 결과 role=null.
    expect(useAuthStore.getState().claims?.role).toBeNull();

    useAuthStore.getState().setServerRole('ADMIN');
    expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
    // 토큰 원본·채널·exp 는 그대로.
    expect(useAuthStore.getState().token).toBe(token);
    expect(useAuthStore.getState().claims?.channel).toBe('INTERNAL');
    expect(useAuthStore.getState().claims?.exp).toBe(9999999999);
  });

  it('setServerRole_null_은_claims_role_을_null_로_되돌린다', () => {
    const token = `h.${b64url({ sub: 'u1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 })}.s`;
    useAuthStore.getState().setToken(token);
    expect(useAuthStore.getState().claims?.role).toBe('REVIEWER');

    useAuthStore.getState().setServerRole(null);
    expect(useAuthStore.getState().claims?.role).toBeNull();
  });

  it('setServerRole_은_claims_가_없으면_no_op', () => {
    useAuthStore.getState().clear();
    expect(useAuthStore.getState().claims).toBeNull();
    useAuthStore.getState().setServerRole('ADMIN');
    expect(useAuthStore.getState().claims).toBeNull();
  });
});
