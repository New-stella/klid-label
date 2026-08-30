// 개발용 화면 토글(`/dev/login` · `/admin/uploads`)의 <런타임 끄기> 축 가드.
//
// 배경: 온프렘 빌드는 두 플래그를 기본 `true` 로 구워 왔다. 문서는 "브링업에서만 임시로 켠다"고
// 하는데, 굳어 버려 그 문장이 실현 불가였다 — 끄려면 재빌드뿐이었다.
// 이제 운영자가 `/etc/klid/frontend.env` 에서 끄고 생성 명령 한 번으로 반영할 수 있어야 한다.

import { afterEach, describe, expect, it, vi } from 'vitest';

import { isDevLoginEnabled } from '@/lib/devLogin';
import { isDevUploadEnabled } from '@/lib/devUpload';
import { RUNTIME_CONFIG_GLOBAL } from '@/lib/runtimeConfig';

function stubRuntimeConfig(value: Record<string, string>): void {
  (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL] = value;
}

afterEach(() => {
  delete (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL];
  vi.unstubAllEnvs();
});

describe('개발용 화면 토글 — 런타임 주입', () => {
  it('빌드에_true_로_구워졌어도_런타임에서_끌_수_있다', () => {
    // given: 온프렘 산출물의 현재 형상 (두 플래그가 true 로 구워짐)
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_DEV_LOGIN_ENABLED', 'true');
    vi.stubEnv('VITE_DEV_UPLOAD_ENABLED', 'true');
    // when: 브링업이 끝나 운영자가 정본에서 껐다
    stubRuntimeConfig({ VITE_DEV_LOGIN_ENABLED: 'false', VITE_DEV_UPLOAD_ENABLED: 'false' });

    // then: 재빌드 없이 비노출
    expect(isDevLoginEnabled()).toBe(false);
    expect(isDevUploadEnabled()).toBe(false);
  });

  it('런타임에_true_면_prod_빌드에서도_노출된다', () => {
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_DEV_LOGIN_ENABLED', '');
    vi.stubEnv('VITE_DEV_UPLOAD_ENABLED', '');
    stubRuntimeConfig({ VITE_DEV_LOGIN_ENABLED: 'true', VITE_DEV_UPLOAD_ENABLED: 'true' });

    expect(isDevLoginEnabled()).toBe(true);
    expect(isDevUploadEnabled()).toBe(true);
  });

  it('런타임_명시값이_DEV_빌드보다_우선한다', () => {
    // 운영자가 명시적으로 끈 것은 어떤 빌드에서도 존중한다 — "명시 지정"이 가장 강한 신호다.
    vi.stubEnv('DEV', true);
    stubRuntimeConfig({ VITE_DEV_LOGIN_ENABLED: 'false', VITE_DEV_UPLOAD_ENABLED: 'false' });

    expect(isDevLoginEnabled()).toBe(false);
    expect(isDevUploadEnabled()).toBe(false);
  });

  it('런타임_미지정이면_기존_판정이_그대로다', () => {
    // 회귀 가드: 생성물이 없는 경로(개발·컨테이너)에서 동작이 바뀌면 안 된다.
    vi.stubEnv('DEV', true);
    expect(isDevLoginEnabled()).toBe(true);
    expect(isDevUploadEnabled()).toBe(true);

    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_DEV_LOGIN_ENABLED', '');
    vi.stubEnv('VITE_DEV_UPLOAD_ENABLED', '');
    expect(isDevLoginEnabled()).toBe(false);
    expect(isDevUploadEnabled()).toBe(false);
  });

  it('런타임_임의값은_노출로_해석하지_않는다', () => {
    // fail-closed — 'true' 만 노출. 오타('1'·'yes')가 켜짐으로 해석되면 안 된다.
    vi.stubEnv('DEV', false);
    stubRuntimeConfig({ VITE_DEV_LOGIN_ENABLED: '1', VITE_DEV_UPLOAD_ENABLED: 'yes' });

    expect(isDevLoginEnabled()).toBe(false);
    expect(isDevUploadEnabled()).toBe(false);
  });
});
