import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  PORTAL_MOUNT_BASENAME,
  REMOTE_EXPOSED_MODULE_NAME,
  resolveRouterBasename,
} from '@/lib/remoteMount';

describe('resolveRouterBasename — 포털 Host 마운트 경로 판정', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('채널_미설정이면_라우터_basename이_없다', () => {
    // given: VITE_BUILD_CHANNEL 미설정 — 기존 내부(관제) 채널 빌드와 동일한 상태
    vi.stubEnv('VITE_BUILD_CHANNEL', '');

    // when / then: 여기서 basename 이 새면 내부 채널의 **모든** 라우트가 포털 마운트 경로
    // 아래로 밀려 전 화면이 404 가 된다(가장 비싼 회귀라 기본값 쪽을 먼저 못 박는다).
    expect(resolveRouterBasename()).toBeUndefined();
  });

  it('채널이_internal이면_라우터_basename이_없다', () => {
    // given
    vi.stubEnv('VITE_BUILD_CHANNEL', 'internal');
    // when / then
    expect(resolveRouterBasename()).toBeUndefined();
  });

  it('채널이_portal이면_마운트_경로가_basename이_된다', () => {
    // given
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    // when / then
    expect(resolveRouterBasename()).toBe(PORTAL_MOUNT_BASENAME);
  });

  it('오타_채널값은_기본값으로_떨어져_basename이_없다_failClosed', () => {
    // given: 판정은 `isPortalEmbedChannel()` 재사용이라 그쪽의 fail-closed 성질을 그대로 물려받는다.
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portall');
    // when / then
    expect(resolveRouterBasename()).toBeUndefined();
  });

  it('마운트_경로는_같은_출처의_절대경로_상수다_외부URL이_아니다', () => {
    // given / when / then: 이 값이 런타임 주입이거나 절대 URL 이면 오픈 리다이렉트 표면이 된다.
    // 빌드타임 상수 + 같은 출처 절대경로임을 형태로 고정한다.
    expect(PORTAL_MOUNT_BASENAME.startsWith('/')).toBe(true);
    expect(PORTAL_MOUNT_BASENAME.startsWith('//')).toBe(false);
    expect(PORTAL_MOUNT_BASENAME).not.toMatch(/^[a-zA-Z][a-zA-Z0-9+.-]*:/);
    expect(REMOTE_EXPOSED_MODULE_NAME.trim().length).toBeGreaterThan(0);
  });
});
