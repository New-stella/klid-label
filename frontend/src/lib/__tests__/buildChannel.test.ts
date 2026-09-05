import { afterEach, describe, expect, it, vi } from 'vitest';

import { BUILD_CHANNELS, DEFAULT_BUILD_CHANNEL, isPortalEmbedChannel } from '@/lib/buildChannel';

describe('isPortalEmbedChannel — 빌드 채널 판정', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('환경변수_미설정이면_기본채널_control이라_false_지금동작유지', () => {
    // given: VITE_BUILD_CHANNEL 미설정 (기존 빌드와 동일한 상태)
    vi.stubEnv('VITE_BUILD_CHANNEL', '');
    // when / then
    expect(isPortalEmbedChannel()).toBe(false);
    expect(DEFAULT_BUILD_CHANNEL).toBe('control');
  });

  it('VITE_BUILD_CHANNEL_control이면_false', () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
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

  /*
   * ★값 축 고정 (2026-08-31 개명 `internal` → `control`).
   *
   * 위 판정 케이스들은 전부 <불리언>만 본다. 그래서 채널 값이 통째로 다른 문자열로
   * 바뀌어도 「유효값이 아니라 기본값으로 떨어져 false」가 되어 그대로 통과한다 —
   * 형식만 보는 검사는 값이 뒤바뀌어도 초록이다. 값 자체를 따로 못 박는다.
   */
  describe('채널 값 자체 (형식이 아니라 값)', () => {
    it('유효_채널은_control과_portal_둘뿐이다', () => {
      expect([...BUILD_CHANNELS]).toEqual(['control', 'portal']);
    });

    it('기본값은_control이다_관제향', () => {
      expect(DEFAULT_BUILD_CHANNEL).toBe('control');
    });

    it('폐기된_구값_internal은_더_이상_유효채널이_아니고_기본값으로_떨어진다', () => {
      // 구 값이 넘어와도 <포털 채널로 오인되지 않는다>는 것까지 함께 못 박는다 —
      // 포털로 오인되면 자체 셸이 사라지고 라우터 basename 이 포털 마운트 경로로 밀린다.
      expect(BUILD_CHANNELS as readonly string[]).not.toContain('internal');
      vi.stubEnv('VITE_BUILD_CHANNEL', 'internal');
      expect(isPortalEmbedChannel()).toBe(false);
    });
  });
});
