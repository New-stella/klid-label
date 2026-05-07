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

  it('setToken시_잘못된_JWT는_claims가_null', () => {
    useAuthStore.getState().setToken('not-a-jwt');
    expect(useAuthStore.getState().token).toBe('not-a-jwt');
    expect(useAuthStore.getState().claims).toBeNull();
  });
});
