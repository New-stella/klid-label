import { type Locator, type Page } from '@playwright/test';

/** 검수 페이지 POM. */
export class ReviewPage {
  readonly page: Page;
  readonly approveBtn: Locator;
  readonly rejectBtn: Locator;
  readonly startBtn: Locator;

  constructor(page: Page) {
    this.page = page;
    this.approveBtn = page.getByRole('button', { name: /승인/ });
    this.rejectBtn = page.getByRole('button', { name: /반려/ });
    this.startBtn = page.getByRole('button', { name: /시작/ });
  }

  /** SPA 내부 navigation — Vite re-optimize 회피. */
  async gotoList() {
    await this.page.evaluate(() => {
      window.history.pushState({}, '', '/review');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await this.page.waitForLoadState('networkidle').catch(() => undefined);
  }

  async gotoDetail(reviewId: number | string) {
    const id = Number(reviewId);
    if (!Number.isFinite(id) || id <= 0) {
      throw new Error(`invalid reviewId: ${reviewId}`);
    }
    await this.page.evaluate((target) => {
      window.history.pushState({}, '', target);
      window.dispatchEvent(new PopStateEvent('popstate'));
    }, `/review/${id}`);
    await this.page.waitForLoadState('networkidle').catch(() => undefined);
  }

  async start() {
    await this.startBtn.click();
  }

  async approve() {
    await this.approveBtn.click();
  }

  async reject() {
    await this.rejectBtn.click();
  }
}
