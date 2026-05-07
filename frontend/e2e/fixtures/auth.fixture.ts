import { test as base, type Page } from '@playwright/test';

import { TEST_USERS, makeTestJwt } from './test-data';

export interface AuthFixtures {
  reviewerPage: Page;
  workerPage: Page;
  portalPage: Page;
}

/**
 * 인증 fixture — 역할별로 토큰을 적재한 Page를 제공한다.
 *
 * 보안:
 * - JWT는 mock (서명 검증은 BE에서 우회 환경 필요)
 * - localStorage가 아닌 zustand store에 직접 set — XSS 위험 최소화 패턴 검증
 */
async function loginViaIngress(page: Page, jwt: string, target: string) {
  // /ingress?token=... 진입 → store 적재 → 채널별 메인으로 redirect
  await page.goto(`/ingress?token=${jwt}`);
  await page.waitForURL(new RegExp(target));
}

export const test = base.extend<AuthFixtures>({
  reviewerPage: async ({ browser }, use) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const jwt = makeTestJwt(TEST_USERS.reviewer);
    await loginViaIngress(page, jwt, '/video/completed');
    await use(page);
    await ctx.close();
  },
  workerPage: async ({ browser }, use) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const jwt = makeTestJwt(TEST_USERS.worker);
    await loginViaIngress(page, jwt, '/video/completed');
    await use(page);
    await ctx.close();
  },
  portalPage: async ({ browser }, use) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const jwt = makeTestJwt(TEST_USERS.portalUser);
    await loginViaIngress(page, jwt, '/portal');
    await use(page);
    await ctx.close();
  },
});

export { expect } from '@playwright/test';
