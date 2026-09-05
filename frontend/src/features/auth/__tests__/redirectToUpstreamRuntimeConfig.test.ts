// 상위 로그인 주소의 <런타임 주입> 축 가드.
//
// 기존 `redirectToUpstream.test.ts` 는 빌드 시점 값(`vi.stubEnv`) 축을 고정한다. 이 파일은
// 그 위에 얹힌 런타임 축만 본다 — 두 파일을 합치지 않는 이유는 폴백이 살아 있는지(빌드 축)와
// 현장값이 이기는지(런타임 축)가 서로 다른 회귀이기 때문이다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { RUNTIME_CONFIG_GLOBAL } from '@/lib/runtimeConfig';

import { isUpstreamLoginConfigured, redirectToUpstream } from '../redirectToUpstream';

function stubRuntimeConfig(value: Record<string, string>): void {
  (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL] = value;
}

describe('redirectToUpstream — 런타임 설정 주입', () => {
  let assignSpy: ReturnType<typeof vi.fn>;
  let originalLocation: Location;

  beforeEach(() => {
    assignSpy = vi.fn();
    originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { ...originalLocation, href: 'http://app.local/video/status', assign: assignSpy },
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { writable: true, value: originalLocation });
    delete (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL];
    vi.unstubAllEnvs();
  });

  it('런타임_주소가_빌드에_구워진_주소를_이긴다', () => {
    // given: 빌드머신이 실주소를 몰라 예시 주소가 구워진 산출물 (실제로 있었던 상태)
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'https://control.example.local/login');
    stubRuntimeConfig({ VITE_CONTROL_LOGIN_URL: 'https://control.site.internal/login' });

    // when
    redirectToUpstream('INTERNAL');

    // then: 재빌드 없이 현장 주소로 나가야 한다
    const target = assignSpy.mock.calls[0][0] as string;
    expect(target.startsWith('https://control.site.internal/login')).toBe(true);
  });

  it('포털_주소도_런타임으로_바꿀_수_있다', () => {
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', 'https://portal.example.local/login');
    stubRuntimeConfig({ VITE_PORTAL_LOGIN_URL: 'https://portal.site.internal/login' });

    redirectToUpstream('PORTAL');

    const target = assignSpy.mock.calls[0][0] as string;
    expect(target.startsWith('https://portal.site.internal/login')).toBe(true);
  });

  it('런타임_설정이_없으면_빌드_값으로_떨어진다', () => {
    // 컨테이너 이미지·`npm run dev` 처럼 생성물이 없는 경로가 깨지면 안 된다.
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');

    redirectToUpstream('INTERNAL');

    const target = assignSpy.mock.calls[0][0] as string;
    expect(target.startsWith('http://control.local/login')).toBe(true);
  });
});

describe('isUpstreamLoginConfigured — 막다른 화면 방지 판정', () => {
  afterEach(() => {
    delete (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL];
    vi.unstubAllEnvs();
  });

  it('주소가_없으면_false_다', () => {
    // given: 설치에서 값을 채우지 않은 상태
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', '');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', '');

    // then: 호출부가 이걸 보고 "무엇이 잘못됐는지 알 수 있는 화면"을 그린다
    expect(isUpstreamLoginConfigured('INTERNAL')).toBe(false);
    expect(isUpstreamLoginConfigured('PORTAL')).toBe(false);
  });

  it('채널별로_따로_판정한다', () => {
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', '');

    expect(isUpstreamLoginConfigured('INTERNAL')).toBe(true);
    expect(isUpstreamLoginConfigured('PORTAL')).toBe(false);
  });

  it('런타임_설정으로_채워도_true_가_된다', () => {
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', '');
    stubRuntimeConfig({ VITE_CONTROL_LOGIN_URL: 'https://control.site.internal/login' });

    expect(isUpstreamLoginConfigured('INTERNAL')).toBe(true);
  });
});
