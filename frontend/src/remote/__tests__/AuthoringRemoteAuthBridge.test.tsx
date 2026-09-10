// [@design INT-013]
//
// 회귀 가드 — **Host 가 넘긴 인계 창구를 진입점이 실제로 받아 등록한다.**
//
// ## 왜 이 가드가 생겼나 (2026-09-10 실측)
// 진입점이 props 를 받지 않던 판이 있었다. Host 는 `<Remote authBridge={...} />` 로 창구를
// 정상적으로 넘기고 있었는데 우리가 그것을 **버리고** 있었고, 포털 채널 창구에는 스토어
// 폴백이 없어(fail-closed) 토큰이 언제나 `null` 이었다. 증상은 이랬다 —
// **포털에 정상 로그인한 상태인데 저작도구 자리에 「인증이 필요합니다」가 뜬다.**
// 타입도 빌드도 테스트도 통과했다. 아무 신호가 없었다.
//
// 그래서 **「props 를 받는가」가 아니라 「등록까지 됐는가」를 값으로** 확인한다. 전자만 보면
// 진입점이 props 를 선언만 하고 흘려도 통과한다.
//
// ## Promise 반환 창구도 함께 잡는다
// 같은 실측에서, 창구를 등록만 하고 **Host 가 돌려주는 `Promise` 를 기다리지 않으면**
// `token.split('.')` 이 `e.split is not a function` 으로 죽어 Remote 가 통째로 내려앉았다
// (Host 화면에는 「저작도구를 불러오지 못했습니다」라는 원인과 무관한 문구가 떴다).
// Host 구현이 실제로 `Promise<string | null>` 이므로 그 모양을 여기서 고정한다.

import { render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  clearHostTokenHandoff,
  getAccessToken,
  type TokenHandoffGateway,
} from '@/features/auth/tokenHandoff';

import AuthoringRemote from '../AuthoringRemote';

vi.mock('@/AuthoringApp', () => ({
  // 이 가드는 「창구가 등록됐는가」만 본다 — 앱 트리 전체는 대역으로 세운다.
  AuthoringApp: () => <div data-testid="authoring-app-stub" />,
}));

const HOST_JWT = 'host-jwt-from-bridge';

/** 포털 Host 의 실제 구현과 같은 모양 — `getAccessToken` 이 **Promise** 다. */
function makePromiseBridge(token: string | null): TokenHandoffGateway {
  return {
    getAccessToken: async () => token,
    refresh: async () => token,
    onUnauthorized: vi.fn(),
    notifyActivity: vi.fn(),
  };
}

describe('AuthoringRemote — Host 인계 창구 수용', () => {
  beforeEach(() => {
    clearHostTokenHandoff();
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
  });

  afterEach(() => {
    clearHostTokenHandoff();
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it('★Host가_넘긴_창구를_등록한다_props를_버리지_않는다', async () => {
    render(<AuthoringRemote authBridge={makePromiseBridge(HOST_JWT)} />);

    // 「props 를 받았다」가 아니라 「그 창구로 토큰이 나온다」를 본다.
    await expect(getAccessToken()).resolves.toBe(HOST_JWT);
  });

  it('★Host가_Promise를_돌려줘도_토큰_문자열이_나온다_객체가_새지_않는다', async () => {
    render(<AuthoringRemote authBridge={makePromiseBridge(HOST_JWT)} />);

    const token = await getAccessToken();
    // Promise 객체가 그대로 흘러가면 여기서 잡힌다 — 실제로 그 값이 JWT 해독에서 터졌다.
    expect(typeof token).toBe('string');
    expect(token).toBe(HOST_JWT);
  });

  it('창구가_없으면_등록하지_않는다_단독_구동_경로를_깨지_않는다', async () => {
    // Host 없이 띄우는 개발 경로(devHostStub)는 자기 대역을 따로 등록한다.
    // 여기서 `undefined` 를 등록해 버리면 그 대역이 조용히 지워진다.
    render(<AuthoringRemote />);

    await expect(getAccessToken()).resolves.toBeNull();
  });

  it('모양이_어긋난_창구는_등록되지_않는다_반쯤_등록된_상태로_남지_않는다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const renamed = {
      getJwt: () => HOST_JWT,
      renew: async () => HOST_JWT,
      onAuthFailure: () => {},
      touch: () => {},
    } as unknown as TokenHandoffGateway;

    render(<AuthoringRemote authBridge={renamed} />);

    await expect(getAccessToken()).resolves.toBeNull();
  });

  it('여러_번_렌더돼도_창구가_유지된다_등록이_멱등이다', async () => {
    const bridge = makePromiseBridge(HOST_JWT);
    const { rerender } = render(<AuthoringRemote authBridge={bridge} />);
    rerender(<AuthoringRemote authBridge={bridge} />);
    rerender(<AuthoringRemote authBridge={bridge} />);

    await expect(getAccessToken()).resolves.toBe(HOST_JWT);
  });
});
