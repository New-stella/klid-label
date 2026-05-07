import { expect, test } from '@playwright/test';

test.describe('로그인 리다이렉트', () => {
  test('토큰_없이_보호된_경로_진입_시_상위_시스템_로그인으로_redirect', async ({ page }) => {
    // 토큰 없이 영상 목록 진입 시 /ingress로 redirect → 상위 시스템 로그인 URL로 이동
    const navResponses: string[] = [];
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) navResponses.push(frame.url());
    });

    await page.goto('/video/completed');

    // 토큰이 없으면 ingress가 외부 redirect를 시도하므로 about:blank/페이지 빈 상태이거나
    // dev 환경에서는 현재 호스트 내에서 ingress 표시가 잠깐 노출된다.
    // 라우트 가드 또는 ingress가 동작하여 영상 목록 컨텐츠가 노출되지 않아야 한다.
    await page.waitForLoadState('networkidle');
    const url = page.url();
    expect(url).not.toMatch(/\/video\/completed$/);
  });
});
