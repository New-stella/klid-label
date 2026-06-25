import { afterEach, describe, expect, it, vi } from 'vitest';

import { isDevLoginEnabled } from '@/lib/devLogin';

describe('isDevLoginEnabled — dev 로그인 노출 토글', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('DEV_빌드면_플래그_무관하게_노출', () => {
    // given: DEV 빌드 + 플래그 미설정
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_DEV_LOGIN_ENABLED', '');
    // when / then
    expect(isDevLoginEnabled()).toBe(true);
  });

  it('prod_빌드_VITE_DEV_LOGIN_ENABLED_true면_노출', () => {
    // given: prod 빌드 + 플래그 true
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_DEV_LOGIN_ENABLED', 'true');
    // when / then
    expect(isDevLoginEnabled()).toBe(true);
  });

  it('prod_빌드_플래그_미설정이면_미노출_failClosed', () => {
    // given: prod 빌드 + 플래그 미설정
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_DEV_LOGIN_ENABLED', '');
    // when / then
    expect(isDevLoginEnabled()).toBe(false);
  });

  it('prod_빌드_플래그_임의값이면_미노출', () => {
    // given: prod 빌드 + 플래그가 'true' 가 아닌 임의값
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_DEV_LOGIN_ENABLED', '1');
    // when / then
    expect(isDevLoginEnabled()).toBe(false);
  });
});
