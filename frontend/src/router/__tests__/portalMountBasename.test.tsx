// 회귀 가드 — 라우터 basename 이 채널을 넘어 새지 않는다.
//
// 저작도구 FE 는 포털 Host 안에 Module Federation Remote 로 실린다. Host 는 자기 경로
// (`PORTAL_MOUNT_BASENAME` — 포털 회신 2026-08-26 으로 확정, 정본은 설계 `INT-013`) 아래에
// 우리를 마운트하므로 포털 채널 산출물만 그 경로를 라우터 basename 으로 가져야 한다.
//
// 이 파일은 **배선 축**을 지킨다 — 값 자체의 고정은 `lib/__tests__/remoteMount.test.ts` 의
// 계약값 블록이 담당한다. 여기서 값을 다시 리터럴로 적으면 두 번째 진실원이 되어, 계약이
// 바뀔 때 한쪽만 고쳐도 통과하는 상태가 생긴다.
//
// ⚠ 이 값이 관제 채널로 새면 **전 라우트가 하위 경로로 밀려 모든 화면이 404** 가 된다.
//   그래서 기본값(미설정)·`control` 쪽을 함께 못 박는다.

import { afterEach, describe, expect, it, vi } from 'vitest';

import { PORTAL_MOUNT_BASENAME } from '@/lib/remoteMount';
import { router } from '@/router';

/** react-router 가 basename 미지정 시 채택하는 기본값. */
const ROUTER_DEFAULT_BASENAME = '/';

describe('라우터 basename 배선 (포털 Host 임베드)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it('채널_미설정이면_라우터_basename이_없다', () => {
    // given: 정적 import 된 router 는 채널 미설정(vitest 기본) 상태에서 평가된 것이다.
    // when / then
    expect(router.basename).toBe(ROUTER_DEFAULT_BASENAME);
  });

  it('채널이_control이면_라우터_basename이_없다', async () => {
    // given
    vi.resetModules();
    vi.stubEnv('VITE_BUILD_CHANNEL', 'control');

    // when: 라우터는 모듈 평가 시점에 생성되므로 env 를 바꾼 뒤 다시 import 해야 한다.
    const { router: controlRouter } = await import('@/router');

    // then
    expect(controlRouter.basename).toBe(ROUTER_DEFAULT_BASENAME);
  });

  it('채널이_portal이면_마운트_경로가_basename이_된다', async () => {
    // given
    vi.resetModules();
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');

    // when
    const { router: portalRouter } = await import('@/router');

    // then
    expect(portalRouter.basename).toBe(PORTAL_MOUNT_BASENAME);
  });
});
