// 포털 채널 세션 되맞춤 — 「거울이 비면 Host 창구에 다시 묻는다」와 그 되풀이 방지.
//
// 고치는 결함: 포털 채널에서 세션(claims)을 <채우는 자리>는 진입 화면 하나뿐인데 <비우는 자리>는
// 셋(401 인터셉터 · 가드 만료 둘)이라, 한 번 비면 영영 다시 차지 않아 모든 라우트 가드가
// 「인증이 필요합니다」 안내를 그렸다. 새로고침 외에 되살릴 방법이 없었다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  clearPortalTokenRejection,
  markPortalTokenRejected,
  syncPortalSessionFromHandoff,
} from '@/features/auth/portalSession';
import {
  clearHostTokenHandoff,
  registerHostTokenHandoff,
  type TokenHandoffGateway,
} from '@/features/auth/tokenHandoff';
import { useAuthStore } from '@/stores/useAuthStore';

/** 서명은 검증하지 않는다(FE 는 표시용 클레임만 해독) — payload 만 맞으면 된다. */
function makeToken(sub: string, expSec: number): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({
    sub,
    role: 'PORTAL_USER',
    channel: 'PORTAL',
    exp: expSec,
  })}.sig`;
}

const ALIVE = Math.floor(Date.now() / 1000) + 3600;

/** Host 가 들고 있는 값을 바꿔 가며 검증한다 — 창구는 언제나 그 시점의 값을 돌려준다. */
let hostToken: string | null = null;
const gateway: TokenHandoffGateway = {
  getAccessToken: () => hostToken,
  refresh: async () => hostToken,
  onUnauthorized: () => {},
  notifyActivity: () => {},
};

describe('포털 채널 세션 되맞춤', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    hostToken = null;
    clearPortalTokenRejection();
    clearHostTokenHandoff();
    useAuthStore.setState({ token: null, claims: null, isHydrated: true });
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    clearHostTokenHandoff();
    clearPortalTokenRejection();
    useAuthStore.setState({ token: null, claims: null, isHydrated: false });
  });

  it('거울이_비어있고_Host가_토큰을_들고_있으면_세션을_되살린다', () => {
    hostToken = makeToken('42', ALIVE);
    registerHostTokenHandoff(gateway);

    expect(syncPortalSessionFromHandoff()).toBe(true);
    expect(useAuthStore.getState().claims?.sub).toBe('42');
    expect(useAuthStore.getState().claims?.channel).toBe('PORTAL');
  });

  it('창구가_아직_없으면_아무것도_하지_않는다', () => {
    expect(syncPortalSessionFromHandoff()).toBe(false);
    expect(useAuthStore.getState().claims).toBeNull();
  });

  it('★거부당한_그_토큰은_다시_집지_않는다_401_되풀이_방지', () => {
    const dead = makeToken('42', ALIVE);
    hostToken = dead;
    registerHostTokenHandoff(gateway);

    // 401 인터셉터가 하는 일 — 무엇이 거부당했는지 남기고 거울을 비운다.
    markPortalTokenRejected(dead);
    useAuthStore.getState().clear();

    expect(syncPortalSessionFromHandoff()).toBe(false);
    expect(useAuthStore.getState().claims).toBeNull();
  });

  it('★Host가_토큰을_갈면_거부_기록이_막지_않는다', () => {
    const dead = makeToken('42', ALIVE);
    registerHostTokenHandoff(gateway);
    markPortalTokenRejected(dead);
    useAuthStore.getState().clear();

    hostToken = makeToken('43', ALIVE);
    expect(syncPortalSessionFromHandoff()).toBe(true);
    expect(useAuthStore.getState().claims?.sub).toBe('43');
  });

  it('Host가_토큰을_들고_있지_않으면_남은_거울을_비운다', () => {
    registerHostTokenHandoff(gateway);
    useAuthStore.setState({
      token: 'stale',
      claims: { sub: '1', role: 'PORTAL_USER', channel: 'PORTAL', exp: ALIVE },
      isHydrated: true,
    });

    expect(syncPortalSessionFromHandoff()).toBe(false);
    expect(useAuthStore.getState().claims).toBeNull();
    expect(useAuthStore.getState().token).toBeNull();
  });

  it('관제_채널에서는_통째로_no_op_이다', () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
    hostToken = makeToken('42', ALIVE);
    registerHostTokenHandoff(gateway);

    expect(syncPortalSessionFromHandoff()).toBe(false);
    expect(useAuthStore.getState().claims).toBeNull();
  });
});
