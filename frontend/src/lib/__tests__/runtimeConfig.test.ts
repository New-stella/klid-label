// 런타임 설정(runtimeConfig) — 빌드 시점 주입을 대체하는 값 해석기의 계약 가드.
//
// 배경: Vite 는 `VITE_*` 를 빌드 시점에 정적 치환한다. 그래서 환경마다 값이 다른 항목
// (상위 로그인 주소·개발용 화면 토글)이 산출물에 굳어 버려, 폐쇄망 반입처럼 "빌드머신이
// 고객 환경의 실주소를 모르는" 모델에서는 배포 가능한 산출물 자체를 만들 수 없었다.
//
// 그래서 값의 정본을 대상 서버의 `/etc/klid/frontend.env` 로 옮기고, 설치가 그 정본에서
// 브라우저가 받는 파일(`klid-config.js`)을 생성해 전역에 심는다. 앱은 그 전역을 먼저 보고,
// 없을 때만 빌드 시점 값으로 떨어진다(개발 워크플로 `npm run dev` 를 깨지 않기 위함).

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  RUNTIME_CONFIG_GLOBAL,
  RUNTIME_CONFIG_KEYS,
  readRuntimeConfig,
  resolveConfig,
} from '@/lib/runtimeConfig';

/** 테스트에서만 쓰는 전역 주입 — 실제로는 `klid-config.js` 가 같은 자리에 심는다. */
function stubRuntimeConfig(value: unknown): void {
  (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL] = value;
}

afterEach(() => {
  delete (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL];
  vi.unstubAllEnvs();
});

describe('resolveConfig — 런타임 → 빌드 → 없음 순서', () => {
  it('런타임_값이_빌드_값을_이긴다', () => {
    // given: 빌드에 구워진 값과 설치 현장의 값이 다르다 (이번 변경의 동기)
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'https://control.example.local/login');
    stubRuntimeConfig({ VITE_CONTROL_LOGIN_URL: 'https://control.site.internal/login' });

    // when / then: 현장값이 이겨야 재빌드 없이 주소를 바꿀 수 있다
    expect(resolveConfig('VITE_CONTROL_LOGIN_URL')).toBe('https://control.site.internal/login');
  });

  it('런타임_값이_없으면_빌드_값으로_떨어진다', () => {
    // given: `npm run dev` / 컨테이너 이미지처럼 런타임 파일이 없는 경로
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'https://control.example.local/login');

    // when / then: 기존 개발 워크플로가 그대로 동작해야 한다
    expect(resolveConfig('VITE_CONTROL_LOGIN_URL')).toBe('https://control.example.local/login');
  });

  it('둘_다_없으면_undefined_다', () => {
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', '');
    expect(resolveConfig('VITE_CONTROL_LOGIN_URL')).toBeUndefined();
  });

  it('런타임_빈_문자열은_미설정으로_보고_빌드_값으로_떨어진다', () => {
    // 생성물이 키를 남기되 값을 비워 둔 경우(운영자가 값을 지운 상태)를 "덮어쓰기"로
    // 읽으면 빌드 기본값까지 함께 사라진다.
    vi.stubEnv('VITE_API_BASE_URL', '/api/v1');
    stubRuntimeConfig({ VITE_API_BASE_URL: '   ' });
    expect(resolveConfig('VITE_API_BASE_URL')).toBe('/api/v1');
  });

  it('전역이_객체가_아니면_무시한다', () => {
    // 생성물이 깨졌거나 다른 스크립트가 같은 이름을 덮어쓴 경우에도 앱이 죽지 않아야 한다.
    vi.stubEnv('VITE_API_BASE_URL', '/api/v1');
    stubRuntimeConfig('망가진 값');
    expect(resolveConfig('VITE_API_BASE_URL')).toBe('/api/v1');
  });

  it('문자열이_아닌_런타임_값은_무시한다', () => {
    vi.stubEnv('VITE_DEV_LOGIN_ENABLED', 'true');
    stubRuntimeConfig({ VITE_DEV_LOGIN_ENABLED: true });
    expect(resolveConfig('VITE_DEV_LOGIN_ENABLED')).toBe('true');
  });
});

describe('readRuntimeConfig — 런타임 전용 읽기', () => {
  it('런타임에_없으면_빌드_값으로_떨어지지_않는다', () => {
    // 「운영자가 명시적으로 지정했는가」를 물어야 하는 자리(개발용 화면 토글)를 위한 축이다.
    vi.stubEnv('VITE_DEV_LOGIN_ENABLED', 'true');
    expect(readRuntimeConfig('VITE_DEV_LOGIN_ENABLED')).toBeUndefined();
  });

  it('런타임_값이_있으면_그대로_돌려준다', () => {
    stubRuntimeConfig({ VITE_DEV_LOGIN_ENABLED: 'false' });
    expect(readRuntimeConfig('VITE_DEV_LOGIN_ENABLED')).toBe('false');
  });
});

describe('허용 키 목록(allowlist)', () => {
  it('전역에_섞여_들어온_허용목록_밖_키는_읽지_않는다', () => {
    // 생성물은 allowlist 로 만들지만, 누군가 전역을 직접 심을 수도 있다.
    // 읽는 쪽도 목록으로 좁혀 두면 그 경로로 값이 흘러들지 않는다.
    stubRuntimeConfig({ JWT_SECRET: '비밀값', VITE_API_BASE_URL: '/api/v1' });
    const keys = RUNTIME_CONFIG_KEYS as readonly string[];
    expect(keys).not.toContain('JWT_SECRET');
    expect(keys).toContain('VITE_API_BASE_URL');
  });

  it('상위_로그인_주소와_개발용_화면_토글이_목록에_있다', () => {
    // 이번 변경의 필수 항목 — 빠지면 재빌드 없이 못 고친다.
    for (const key of [
      'VITE_CONTROL_LOGIN_URL',
      'VITE_PORTAL_LOGIN_URL',
      'VITE_DEV_LOGIN_ENABLED',
      'VITE_DEV_UPLOAD_ENABLED',
    ]) {
      expect(RUNTIME_CONFIG_KEYS as readonly string[], `${key} 누락`).toContain(key);
    }
  });
});
