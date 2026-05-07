import { defineConfig, devices } from '@playwright/test';

/**
 * Phase 12 — Playwright E2E 설정.
 *
 * 실행:
 *   npm run e2e        — headless 실행 (CI 기본)
 *   npm run e2e:ui     — Playwright UI 모드 (개발자용)
 *   npm run e2e:install — 브라우저 바이너리 설치 (최초 1회)
 *
 * webServer는 reuseExistingServer를 사용해 개발자가 이미 dev server를 띄운 경우 재사용.
 */
export default defineConfig({
  testDir: './e2e/specs',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [['html', { open: 'never' }], ['list']] : [['list']],
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: process.env.E2E_CAPTURE ? 'on' : 'only-on-failure',
    headless: true,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
