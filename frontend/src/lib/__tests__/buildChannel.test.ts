import { afterEach, describe, expect, it, vi } from 'vitest';

import { DEFAULT_BUILD_CHANNEL, isPortalEmbedChannel } from '@/lib/buildChannel';

describe('isPortalEmbedChannel — 빌드 채널 판정', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('환경변수_미설정이면_기본채널_internal이라_false_지금동작유지', () => {
    // given: VITE_BUILD_CHANNEL 미설정 (기존 빌드와 동일한 상태)
    vi.stubEnv('VITE_BUILD_CHANNEL', '');
    // when / then
    expect(isPortalEmbedChannel()).toBe(false);
    expect(DEFAULT_BUILD_CHANNEL).toBe('internal');
  });

  it('VITE_BUILD_CHANNEL_internal이면_false', () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'internal');
    expect(isPortalEmbedChannel()).toBe(false);
  });

  it('VITE_BUILD_CHANNEL_portal이면_true', () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    expect(isPortalEmbedChannel()).toBe(true);
  });

  it('임의값_오타는_모두_기본값으로_떨어진다_failClosed', () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portall');
    expect(isPortalEmbedChannel()).toBe(false);
  });
});
