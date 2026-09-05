import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RouteObject } from 'react-router-dom';

import { RUNTIME_CONFIG_GLOBAL } from '@/lib/runtimeConfig';

// [@design SCREEN-004]
/**
 * 개발용 화면 토글이 **꺼졌을 때 라우트가 등록되지 않는다**는 회귀 가드.
 *
 * ## 왜 이 축이 따로 필요한가
 *
 * 이 저장소의 개발용 화면(`/dev/login` · `/admin/uploads`)은 **산출물에 실려 나간다** —
 * `DevLoginPage-*.js` 청크가 기본 · 토글 ON · control · portal **네 형상 모두**에서 실측 1건이다.
 * 판정(`isDevLoginEnabled()`)이 런타임 설정까지 읽는 **함수**라 번들러가 접지 못하기 때문이고,
 * 그건 「재빌드 없이 현장에서 끌 수 있어야 한다」는 요구의 **대가이지 결함이 아니다**
 * (근거 전문은 `lib/devLogin.ts`).
 *
 * ⇒ 그러므로 **안전의 근거는 「번들에 없다」가 아니라 「도달할 수 없다」**이고, 그 첫 칸이 이
 *   라우트 미등록이다. 그런데 이 축이 **어디에서도 검증되지 않고 있었다** —
 *   `lib/__tests__/devToggleRuntimeConfig.test.ts` 는 <판정 함수>만 보고,
 *   `router/__tests__/channelRouteSplit.test.tsx` 는 토글이 <켜진> 상태의 존재만 본다.
 *   긍정 케이스만 있으면 「꺼도 라우트가 남는」 회귀가 그대로 통과한다.
 *
 * ⚠ 나머지 두 칸은 백엔드 몫이라 여기서 볼 수 없다 — `DevTokenController` 가
 *   `@ConditionalOnProperty(havingValue="true")` 라 꺼지면 `/v1/dev/tokens` 가 404 이고,
 *   `DevToggleProfileGuard` 가 `prd`·`stg` 에서 그 토글이 켜져 있으면 기동을 거부한다.
 *   프론트 가드가 초록이어도 그 둘이 살아 있어야 3중이 성립한다.
 *
 * ## 전용 파일인 이유
 * 라우트 표는 **모듈이 처음 평가될 때** 만들어진다. 한 파일에서 토글을 바꿔 가며 재평가하면
 * 케이스끼리 오염되므로, 케이스마다 `vi.resetModules()` 후 동적 import 로 표를 다시 만든다.
 */

function stubRuntimeConfig(value: Record<string, string>): void {
  (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL] = value;
}

/** 라우트 트리를 전체 경로 문자열 집합으로 편다 — 중첩 자식까지 본다. */
function flattenPaths(routes: readonly RouteObject[], parent = ''): string[] {
  const out: string[] = [];
  for (const route of routes) {
    const raw = route.path ?? '';
    const full = raw.startsWith('/')
      ? raw
      : raw === ''
        ? parent
        : `${parent.replace(/\/$/, '')}/${raw}`;
    if (route.path !== undefined) out.push(full);
    if (route.children) out.push(...flattenPaths(route.children, full));
  }
  return out;
}

async function loadRoutes(runtime: Record<string, string>): Promise<string[]> {
  vi.resetModules();
  stubRuntimeConfig(runtime);
  const mod = await import('@/router');
  return flattenPaths(mod.router.routes);
}

describe('개발용 화면 토글이 꺼지면 라우트가 등록되지 않는다', () => {
  afterEach(() => {
    delete (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL];
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it('토글이_켜져_있으면_두_주소가_모두_등록된다', async () => {
    // 대조 케이스가 먼저다 — 이것이 없으면 아래 「없다」가 <가드가 늘 참인> 상태로 통과한다.
    const paths = await loadRoutes({
      VITE_DEV_LOGIN_ENABLED: 'true',
      VITE_DEV_UPLOAD_ENABLED: 'true',
    });

    expect(paths).toContain('/dev/login');
    expect(paths).toContain('/admin/uploads');
  });

  it('★개발용_로그인을_끄면_그_주소가_라우트_표에서_사라진다', async () => {
    // 청크는 산출물에 남아 있어도 이 주소로는 갈 수 없다 — 그것이 프론트 쪽 안전의 근거다.
    const paths = await loadRoutes({
      VITE_DEV_LOGIN_ENABLED: 'false',
      VITE_DEV_UPLOAD_ENABLED: 'true',
    });

    expect(paths).not.toContain('/dev/login');
    // 같은 표의 나머지가 함께 사라지지 않았음을 짝으로 본다(끄기가 과하게 먹으면 그것도 결함이다).
    expect(paths).toContain('/admin/uploads');
    expect(paths).toContain('/ingress');
  });

  it('★파일_업로드를_끄면_그_주소가_라우트_표에서_사라진다', async () => {
    const paths = await loadRoutes({
      VITE_DEV_LOGIN_ENABLED: 'true',
      VITE_DEV_UPLOAD_ENABLED: 'false',
    });

    expect(paths).not.toContain('/admin/uploads');
    expect(paths).toContain('/dev/login');
    // 「관리자」 그룹의 나머지는 토글과 무관하다 — 항목 하나가 그룹을 데려가면 안 된다.
    expect(paths).toContain('/admin/endpoints');
  });

  it('★둘_다_끄면_둘_다_사라지고_운영_화면은_그대로다', async () => {
    const paths = await loadRoutes({
      VITE_DEV_LOGIN_ENABLED: 'false',
      VITE_DEV_UPLOAD_ENABLED: 'false',
    });

    expect(paths).not.toContain('/dev/login');
    expect(paths).not.toContain('/admin/uploads');
    for (const kept of ['/ingress', '/forbidden', '/dashboard', '/admin/endpoints']) {
      expect(paths).toContain(kept);
    }
  });
});
