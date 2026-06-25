import { afterEach, describe, expect, it, vi } from 'vitest';

import { isDevUploadEnabled } from '@/lib/devUpload';

describe('isDevUploadEnabled — dev 업로드(오토라벨 테스트) 노출 토글', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('DEV_빌드면_플래그_무관하게_노출', () => {
    // given: DEV 빌드 + 플래그 미설정
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_DEV_UPLOAD_ENABLED', '');
    // when / then
    expect(isDevUploadEnabled()).toBe(true);
  });

  it('prod_빌드_VITE_DEV_UPLOAD_ENABLED_true면_노출', () => {
    // given: prod 빌드 + 플래그 true
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_DEV_UPLOAD_ENABLED', 'true');
    // when / then
    expect(isDevUploadEnabled()).toBe(true);
  });

  it('prod_빌드_플래그_미설정이면_미노출_failClosed', () => {
    // given: prod 빌드 + 플래그 미설정
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_DEV_UPLOAD_ENABLED', '');
    // when / then
    expect(isDevUploadEnabled()).toBe(false);
  });

  it('prod_빌드_플래그_임의값이면_미노출', () => {
    // given: prod 빌드 + 플래그가 'true' 가 아닌 임의값
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_DEV_UPLOAD_ENABLED', '1');
    // when / then
    expect(isDevUploadEnabled()).toBe(false);
  });
});
