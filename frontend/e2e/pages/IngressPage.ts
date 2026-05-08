import { type Page } from '@playwright/test';

/**
 * `/ingress` 진입 페이지 POM.
 *
 * <p>토큰 없이 진입 시 상위 시스템(관제/포털) 로그인으로 redirect 되며,
 * 토큰이 있으면 채널별 메인 (INTERNAL → /dashboard, PORTAL → /portal) 으로 이동.
 */
export class IngressPage {
  readonly page: Page;

  constructor(page: Page) {
    this.page = page;
  }

  /** 토큰 없이 ingress 진입 (가드 동작 검증용). */
  async gotoWithoutToken() {
    await this.page.goto('/ingress');
  }

  /** 임의 보호 경로 직접 진입 — 가드가 ingress 또는 외부 redirect 로 보내는지 검증. */
  async gotoProtected(path = '/dashboard') {
    await this.page.goto(path);
  }

  /** 토큰을 URL 파라미터로 전달하여 ingress 진입. */
  async gotoWithToken(token: string) {
    await this.page.goto(`/ingress?token=${token}`);
  }
}
