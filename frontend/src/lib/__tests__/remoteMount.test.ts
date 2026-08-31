import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  PORTAL_MOUNT_BASENAME,
  REMOTE_BUNDLE_BASE_PATH,
  REMOTE_ENTRY_CACHE_CONTROL,
  REMOTE_ENTRY_FILE_NAME,
  REMOTE_EXPOSED_MODULE_NAME,
  REMOTE_NAME,
  resolveRouterBasename,
} from '@/lib/remoteMount';

describe('resolveRouterBasename — 포털 Host 마운트 경로 판정', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('채널_미설정이면_라우터_basename이_없다', () => {
    // given: VITE_BUILD_CHANNEL 미설정 — 기존 관제 채널 빌드와 동일한 상태
    vi.stubEnv('VITE_BUILD_CHANNEL', '');

    // when / then: 여기서 basename 이 새면 내부 채널의 **모든** 라우트가 포털 마운트 경로
    // 아래로 밀려 전 화면이 404 가 된다(가장 비싼 회귀라 기본값 쪽을 먼저 못 박는다).
    expect(resolveRouterBasename()).toBeUndefined();
  });

  it('채널이_control이면_라우터_basename이_없다', () => {
    // given
    vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
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
  });

  it('번들_서빙_경로도_같은_출처의_절대경로다_끝에_슬래시가_있다', () => {
    // given / when / then: asset base 라 접두어로 이어 붙는다 — 끝 슬래시가 없으면
    // `/label-remoteassets/...` 처럼 붙어 청크만 404 가 되고 화면이 절반만 뜬다.
    expect(REMOTE_BUNDLE_BASE_PATH.startsWith('/')).toBe(true);
    expect(REMOTE_BUNDLE_BASE_PATH.startsWith('//')).toBe(false);
    expect(REMOTE_BUNDLE_BASE_PATH).not.toMatch(/^[a-zA-Z][a-zA-Z0-9+.-]*:/);
    expect(REMOTE_BUNDLE_BASE_PATH.endsWith('/')).toBe(true);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// 계약값 고정 — 형식 검사와 **짝**으로 둔다.
//
// ⚠ 위 형식 검사만으로는 값이 서로 뒤바뀌거나 엉뚱한 이름으로 바뀌어도 통과한다
//   (`'./PortalApp'` → `'./Whatever'` 도 "비어 있지 않은 문자열"이다). 이 값들은 우리가
//   고를 수 있는 값이 아니라 **포털이 정본으로 제시한 계약**이고, 어긋나면 빌드가 아니라
//   **런타임에** Host 가 원격 모듈을 못 찾는 형태로 드러난다. 그래서 값 자체를 못 박는다.
//
// 정본: 설계 `INT-013` 「진입점·마운트·서빙 규약」 (포털 회신 2026-08-26).
// ─────────────────────────────────────────────────────────────────────────────
describe('포털 MF 임베딩 계약값 (포털이 정하고 저작도구가 맞춘 값)', () => {
  it('원격_모듈명과_노출_모듈명이_포털_확정값이다', () => {
    // given / when / then
    expect(REMOTE_NAME).toBe('authoring');
    expect(REMOTE_EXPOSED_MODULE_NAME).toBe('./PortalApp');
  });

  it('Host가_가져갈_import_지정자가_authoring_PortalApp_이다', () => {
    // given: Host 는 원격 모듈명과 노출 모듈명을 **이어서** 부른다. 둘 중 하나만 고치면
    //        지정자가 깨지므로, 각각이 아니라 이어 붙인 결과를 함께 못 박는다.
    // when
    const specifier = `${REMOTE_NAME}/${REMOTE_EXPOSED_MODULE_NAME.replace(/^\.\//, '')}`;

    // then
    expect(specifier).toBe('authoring/PortalApp');
  });

  it('마운트_경로와_번들_서빙_경로가_포털_확정값이다', () => {
    // given / when / then: 마운트 경로가 어긋나면 전 화면 404, 서빙 경로가 어긋나면
    // 청크만 404 라 증상이 서로 다르다 — 두 축을 각각 고정한다.
    expect(PORTAL_MOUNT_BASENAME).toBe('/workspace/authoring');
    expect(REMOTE_BUNDLE_BASE_PATH).toBe('/label-remote/');
  });

  it('진입_파일은_remoteEntry_js이고_재검증을_강제한다', () => {
    // given: 진입 파일만 이름에 해시가 붙지 않아(붙으면 Host 가 주소를 알 수 없다)
    //        내용이 바뀌어도 주소가 그대로다. 캐시를 그냥 두면 브라우저가 옛 진입 파일을
    //        계속 써서, 이미 지워진 해시 청크를 가리키는 채로 배포 직후 화면이 깨진다.
    // when / then
    expect(REMOTE_ENTRY_FILE_NAME).toBe('remoteEntry.js');
    // `no-store`(아예 캐시 안 함)로 바꾸면 매번 전송이라 더 비싸고, 목적(최신성)에는
    // `no-cache`(쓰기 전 재검증 → 안 바뀌었으면 304)로 충분하다.
    expect(REMOTE_ENTRY_CACHE_CONTROL).toBe('no-cache');
  });
});
