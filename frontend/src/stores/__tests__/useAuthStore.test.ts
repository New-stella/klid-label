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

  it('채널이_없는_토큰은_여전히_통째로_거절한다', () => {
    // ★역할을 관대하게 읽는 것이 「아무거나 받는다」는 뜻이 아니다. 필수 클레임이 없으면
    //   그대로 무효 토큰이다 — 이 대조가 없으면 위 완화가 어디까지인지 알 수 없다.
    const token = `header.${b64url({ sub: 'u-3', role: 'ADMIN', exp: 9999999999 })}.sig`;

    useAuthStore.getState().setToken(token);

    expect(useAuthStore.getState().claims).toBeNull();
    expect(useAuthStore.getState().token).toBeNull();
  });
});
