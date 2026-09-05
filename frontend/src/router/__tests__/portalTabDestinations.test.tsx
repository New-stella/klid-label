// 회귀 가드 — **본문 상단 이동 탭의 목적지가 실제로 화면을 연다**(못 찾은 주소가 아니다).
//
// ★ 왜 이 축이 따로 필요한가
//   탭 선언(`lib/portalNav`)과 라우트 표(`router/index`)는 **서로 다른 파일**이고 서로를 읽지
//   않는다. 그래서 탭에 목적지를 먼저 세우고 화면을 나중에 만드는 순서가 되면, 그 사이 동안
//   탭은 멀쩡히 뜨는데 누르면 못 찾은 주소로 떨어진다 — **실제로 그런 상태였다**(증강 탭이
//   화면보다 먼저 섰다). 탭 컴포넌트 시험은 라우트 표를 읽지 않아 이 축에 원리적으로 눈이 멀고,
//   라우트 시험은 탭 선언을 읽지 않아 목적지가 늘어도 조용하다.
//
// ★★ **프로덕션 라우트 표를 실제로 읽는다.** 시험이 자체 라우터 트리를 새로 조립하면 프로덕션
//   에서 그 경로를 지워도 통과한다 — 지키려는 것이 배선인데 배선을 안 읽는 꼴이다.
//
// ★★★ 「경로 문자열이 목록에 있다」가 아니라 **라우터가 실제로 그 주소를 무엇으로 푸는지**를 본다.
//   못 찾은 주소를 받는 자리(`*`)가 매칭되면 그것이 곧 404 화면이므로, 매칭 자체는 성공해도
//   사용자에게는 화면이 없는 것이다.
//
// ⚠ 새 목적지를 탭에 추가하면 이 시험이 **자동으로** 그 목적지까지 검사한다(목록을 여기 복제하지
//   않는다). 화면을 만들지 않고 탭만 추가하면 여기가 먼저 빨개진다 — 그것이 이 파일의 목적이다.
//
// @design SHELL-002
// @design NAV-002
// @design SCREEN-044
import { afterEach, describe, expect, it, vi } from 'vitest';
import { matchRoutes } from 'react-router-dom';
import type { RouteObject } from 'react-router-dom';

import { PORTAL_CONTENT_TABS } from '@/lib/portalNav';

/** 못 찾은 주소를 받는 자리. 여기에 걸리면 화면이 아니라 404 다. */
const NOT_FOUND_PATH = '*';

async function loadPortalRoutes(): Promise<readonly RouteObject[]> {
  vi.resetModules();
  vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
  const mod = await import('@/router');
  return mod.router.routes;
}

describe('포털 이동 탭의 목적지', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  // 스캐너 자신에 대한 가드가 먼저다 — 매칭기가 아무것도 못 풀면 아래 단언이 공짜로 통과한다.
  it('탭_선언이_비어_있지_않고_라우트_표를_실제로_읽는다', async () => {
    const routes = await loadPortalRoutes();

    expect(PORTAL_CONTENT_TABS.length).toBeGreaterThan(0);
    expect(matchRoutes(routes as RouteObject[], '/portal')).not.toBeNull();
    // 대조군 — 아무 데도 없는 주소는 못 찾은 자리로 떨어진다(그것을 실제로 구분할 수 있어야 한다).
    const miss = matchRoutes(routes as RouteObject[], '/portal/이런-주소는-없다');
    expect(miss).not.toBeNull();
    expect(miss?.[miss.length - 1].route.path).toBe(NOT_FOUND_PATH);
  });

  it.each(PORTAL_CONTENT_TABS.map((t) => ({ label: t.label, path: t.path })))(
    '★$label 탭($path)이 못 찾은 주소가 아니라 화면을 연다',
    async ({ path }) => {
      const routes = await loadPortalRoutes();

      const matched = matchRoutes(routes as RouteObject[], path);
      expect(matched, `${path} 가 어느 라우트에도 걸리지 않는다`).not.toBeNull();

      const leaf = matched?.[matched.length - 1].route;
      expect(leaf?.path, `${path} 가 못 찾은 주소 자리로 떨어진다(404)`).not.toBe(NOT_FOUND_PATH);
      expect(leaf?.element, `${path} 에 그릴 화면이 없다`).toBeTruthy();
    },
  );
});
