import { test as base, type Page, request as pwRequest } from '@playwright/test';

import { TEST_USERS } from './test-data';

export interface AuthFixtures {
  reviewerPage: Page;
  workerPage: Page;
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
 */
const BE_BASE = process.env.E2E_BE_URL || 'http://127.0.0.1:8080';

async function issueDevToken(claims: {
  role: 'REVIEWER' | 'WORKER' | 'PORTAL_USER';
  channel: 'INTERNAL' | 'PORTAL';
  userNo: string;
  name?: string;
}): Promise<string> {
  const ctx = await pwRequest.newContext();
  try {
    const res = await ctx.post(`${BE_BASE}/api/v1/dev/tokens`, {
      data: {
        role: claims.role,
        channel: claims.channel,
        userNo: claims.userNo,
        name: claims.name,
        expSeconds: 3600,
      },
    });
    if (!res.ok()) {
      throw new Error(`dev token 발급 실패: ${res.status()} ${await res.text()}`);
    }
    const body = (await res.json()) as { data?: { token?: string } };
    if (!body.data?.token) {
      throw new Error(`dev token 응답에 token 없음: ${JSON.stringify(body)}`);
    }
    return body.data.token;
  } finally {
    await ctx.dispose();
  }
}

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
