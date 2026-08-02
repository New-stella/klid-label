import { test as base, type Page } from '@playwright/test';

import { issueDevToken } from './be-client';
import { TEST_USERS } from './test-data';

export interface AuthFixtures {
  reviewerPage: Page;
  workerPage: Page;
  labelerPage: Page;
  portalPage: Page;
}

/**
 * BE 가 발급한 실제 서명 JWT 를 받아 ingress 로 진입한다.
 *
 * <p>보안:
 * <ul>
 *   <li>실제 시크릿/계정은 사용하지 않음 — BE {@code /v1/dev/tokens} 는 prd 환경에서 비활성화.</li>
 *   <li>토큰은 매 테스트마다 신규 발급 (만료/재사용 위험 회피).</li>
 *   <li>토큰은 ingress URL 파라미터로 전달 — localStorage 우회 (XSS 표면 축소).</li>
 * </ul>
 *
 * <p>토큰 발급 자체는 {@link ./be-client} 가 담당한다 (워크플로 픽스처 해석과 공유).
 */
async function loginViaIngress(page: Page, jwt: string, target: RegExp) {
  await page.goto(`/ingress?token=${jwt}`);
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
