import { test as base, type Page } from '@playwright/test';

import { LOCAL_STORAGE_TOKEN_KEY } from '@/features/auth/tokenIngress';

import { issueDevToken } from './be-client';
import { TEST_USERS } from './test-data';

export interface AuthFixtures {
  reviewerPage: Page;
  workerPage: Page;
  labelerPage: Page;
  portalPage: Page;
}

/**
 * BE 가 발급한 실제 서명 JWT 를 <b>localStorage 인계</b>로 넘겨 ingress 로 진입한다.
 *
 * <p>인계 경로:
 * <ul>
 *   <li>토큰을 같은 origin 의 {@code localStorage[LOCAL_STORAGE_TOKEN_KEY]} 에 심고 {@code /ingress}
 *       로 진입한다. 이는 <b>운영에서 관제서버·포털이 실제로 쓰는 인계 경로와 동일</b>하다 —
 *       저작도구는 독립 로그인 UI 없이 상위 시스템이 브라우저 저장소에 둔 JWT 를 인계받는다.
 *       따라서 e2e 가 검증하는 진입 경로가 운영 경로와 갈리지 않는다.</li>
 *   <li>주입은 {@link Page#addInitScript} 로 한다. 이 스크립트는 <b>문서마다 앱 스크립트보다 먼저</b>
 *       실행되므로 ingress 페이지가 토큰을 읽는 시점에 값이 이미 있다. {@code goto} 이후에
 *       {@code evaluate} 로 심으면 페이지가 이미 토큰을 못 찾고 지나간 뒤라 늦다.</li>
 *   <li>저장 형태는 <b>원문 JWT 문자열</b>이다 (JSON 래핑 아님) — 앱의
 *       {@code tokenIngress.resolveToken} 이 그 값을 그대로 형식·alg 검증한다.</li>
 *   <li>키는 {@link LOCAL_STORAGE_TOKEN_KEY} 상수를 그대로 쓴다. 문자열을 복제하면 상수가 바뀔 때
 *       픽스처만 조용히 깨진다.</li>
 * </ul>
 *
 * <p>구 방식({@code /ingress?token=...} URL 쿼리)은 <b>더 이상 동작하지 않는다</b>. 확정 정책
 * (ADR-012)상 상위 시스템은 동일 origin 브라우저 저장소로만 JWT 를 인계하며 {@code ?token=}
 * 채널은 폐기됐다 — URL 에 실린 JWT 는 접근 로그·리퍼러·브라우저 히스토리에 잔존해 사후 회수가
 * 불가능하다(CWE-598 / CWE-200). 앱의 {@code DEFAULT_INGRESS_STRATEGY} 가
 * {@code 'localStorage'} 라 URL 쿼리는 구조적으로 읽히지 않는다.
 * 테스트가 {@code VITE_TOKEN_INGRESS=all} 로 그 채널을 되살리지 않는다.
 *
 * <p>보안:
 * <ul>
 *   <li>실제 시크릿/계정은 사용하지 않음 — BE {@code /v1/dev/tokens} 는 prd 환경에서 비활성화.</li>
 *   <li>토큰은 매 테스트마다 신규 발급 (만료/재사용 위험 회피).</li>
 *   <li>토큰이 URL 에 실리지 않으므로 실패 시 트레이스·스크린샷 아티팩트의 URL 에 남지 않는다.</li>
 * </ul>
 *
 * <p>토큰 발급 자체는 {@link ./be-client} 가 담당한다 (워크플로 픽스처 해석과 공유).
 */
async function loginViaIngress(page: Page, jwt: string, target: RegExp) {
  await page.addInitScript(
    ([key, token]: [string, string]) => {
      try {
        window.localStorage.setItem(key, token);
      } catch {
        // private mode / quota 등에서의 예외는 무시 — 진입 실패로 드러난다.
      }
    },
    [LOCAL_STORAGE_TOKEN_KEY, jwt] as [string, string],
  );
  await page.goto('/ingress');
  await page.waitForURL(target);
}

export const test = base.extend<AuthFixtures>({
  reviewerPage: async ({ browser }, use) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const jwt = await issueDevToken(TEST_USERS.reviewer);
    await loginViaIngress(page, jwt, /\/dashboard/);
    await use(page);
    await ctx.close();
  },
  workerPage: async ({ browser }, use) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const jwt = await issueDevToken(TEST_USERS.worker);
    await loginViaIngress(page, jwt, /\/dashboard/);
    await use(page);
    await ctx.close();
  },
  labelerPage: async ({ browser }, use) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const jwt = await issueDevToken(TEST_USERS.labeler);
    await loginViaIngress(page, jwt, /\/dashboard/);
    await use(page);
    await ctx.close();
  },
  portalPage: async ({ browser }, use) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const jwt = await issueDevToken(TEST_USERS.portalUser);
    await loginViaIngress(page, jwt, /\/portal/);
    await use(page);
    await ctx.close();
  },
});

export { expect } from '@playwright/test';
