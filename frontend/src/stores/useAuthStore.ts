import { create } from 'zustand';

import { isKnownRole } from '@/lib/authz';
import { Channel, type Role, type TokenClaims } from '@/lib/api/types';

const SESSION_KEY = 'klid_jwt';

interface AuthState {
  token: string | null;
  claims: TokenClaims | null;
  isHydrated: boolean;
  setToken: (token: string) => void;
  /**
   * Phase 2 (권한 자가 부여) — 새 토큰을 디코드하여 claims 와 함께 일괄 갱신한다.
   * `setToken` 과 동일 동작이지만 호출 의도(토큰 + 클레임 동시 교체)를 명확히 한다.
   */
  setTokenAndClaims: (token: string) => void;
  clear: () => void;
  hydrate: () => void;
}

// JWT payload base64url decode (보안: 무결성 검증은 BE에서 — FE는 표시용 클레임만 추출)
function decodeJwtPayload(token: string): TokenClaims | null {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const padded = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padding = padded.length % 4 === 0 ? '' : '='.repeat(4 - (padded.length % 4));
    const binary = atob(padded + padding);
    // UTF-8 safe decode — Array.from(binary) 는 surrogate pair 만 처리하고
    // 한글(EUC-KR/UTF-8 mix) 멀티바이트는 깨질 수 있으므로 TextDecoder 로 정확히 디코드.
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    const json =
      typeof TextDecoder !== 'undefined'
        ? new TextDecoder('utf-8').decode(bytes)
        : decodeURIComponent(
            Array.from(binary)
              .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
              .join(''),
          );
    const raw = JSON.parse(json) as Record<string, unknown>;

    const sub = typeof raw.sub === 'string' ? raw.sub : '';
    // Phase 2 — role 클레임이 비어 있는 인증 토큰(권한 자가 부여 대기 상태)을 허용한다.
    //
    // ★모르는 역할 값 하나로 인증 전체를 버리지 않는다. 예전에는 우리가 아는 목록에 없는 값이
    //   오면 클레임 객체를 통째로 null 로 만들어, 그 사용자가 **로그인 자체를 못 했다**. 서버가
    //   역할을 새로 늘리는 것은 정상적인 일이고 그때마다 화면이 진입 불가가 되는 것은 fail-closed
    //   가 아니라 그냥 고장이다(실제로 관리자 역할이 늘었을 때 그 일이 일어났다).
    //
    //   그래서 모르는 값은 **역할 미부여로 낮춘다** — 인증은 살아 있고 권한만 없다. 권한이 없는
    //   상태의 처리는 이미 있다(내부 채널이면 역할 부여 안내로 보낸다). 인가 판정은 어차피
    //   서버가 소유하므로, 화면이 모르는 값에 권한을 주는 일은 이 경로 어디에도 없다.
    const rawRole = raw.role;
    const hasRole = rawRole !== undefined && rawRole !== null && rawRole !== '';
    const role: Role | null = hasRole && isKnownRole(rawRole) ? rawRole : null;
    const channel = isChannel(raw.channel) ? raw.channel : null;
    const exp = typeof raw.exp === 'number' ? raw.exp : 0;
    const name = typeof raw.name === 'string' ? raw.name : undefined;

    if (!sub || !channel || !exp) return null;
    return { sub, role, channel, exp, name };
  } catch {
    return null;
  }
}

function isChannel(value: unknown): value is Channel {
  return typeof value === 'string' && (Object.values(Channel) as string[]).includes(value);
}

export const useAuthStore = create<AuthState>((set) => ({
  token: null,
  claims: null,
  isHydrated: false,
  setToken: (token: string) => {
    const claims = decodeJwtPayload(token);
    if (!claims) return; // 유효하지 않은 토큰은 저장하지 않음
    sessionStorage.setItem(SESSION_KEY, token);
    set({ token, claims });
  },
  setTokenAndClaims: (token: string) => {
    const claims = decodeJwtPayload(token);
    if (!claims) return;
    sessionStorage.setItem(SESSION_KEY, token);
    set({ token, claims });
  },
  clear: () => {
    sessionStorage.removeItem(SESSION_KEY);
    set({ token: null, claims: null });
  },
  hydrate: () => {
    const stored = sessionStorage.getItem(SESSION_KEY);
    if (stored) {
      const claims = decodeJwtPayload(stored);
      // 만료된 토큰은 무시
      if (claims && claims.exp > Math.floor(Date.now() / 1000)) {
        set({ token: stored, claims, isHydrated: true });
        return;
      }
      // 만료됐으면 스토리지에서도 제거
      sessionStorage.removeItem(SESSION_KEY);
    }
    set({ isHydrated: true });
  },
}));
