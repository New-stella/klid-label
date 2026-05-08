import { test, expect } from '../fixtures/auth.fixture';

/**
 * PORTAL_USER 채널 가드 검증 — 내부 화면 접근 시 차단.
 *
 * <p>BE 는 SecurityConfig {@code /v1/manage/**} 등에서 401/403 으로 차단,
 * FE 는 ChannelGuard 가 INTERNAL 전용 경로 접근 시 /forbidden 또는 /portal 로 강제.
 */
test.describe('PORTAL_USER 채널 가드', () => {
  test('포털_홈_정상_진입', async ({ portalPage }) => {
    // fixture 가 /portal 진입까지 보장 — 추가 단언만.
    await expect(portalPage).toHaveURL(/\/portal/);
  });

  test('내부_대시보드_직접_진입_차단', async ({ portalPage }) => {
    await portalPage.evaluate(() => {
      window.history.pushState({}, '', '/dashboard');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await portalPage.waitForLoadState('networkidle').catch(() => undefined);

    // INTERNAL 전용 경로 — 가드가 forbidden 또는 portal 로 redirect.
    await expect(portalPage).not.toHaveURL(/\/dashboard$/);
  });

  test('내부_관리_화면_직접_진입_차단', async ({ portalPage }) => {
    await portalPage.evaluate(() => {
      window.history.pushState({}, '', '/manage/users');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await portalPage.waitForLoadState('networkidle').catch(() => undefined);

    await expect(portalPage).not.toHaveURL(/\/manage\/users$/);
  });
});
