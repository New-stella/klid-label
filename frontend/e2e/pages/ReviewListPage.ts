import { type Locator, type Page } from '@playwright/test';

/** 검수 목록 (SCR-REV-001) POM. */
export class ReviewListPage {
  readonly page: Page;
  readonly listSection: Locator;

  constructor(page: Page) {
    this.page = page;
    this.listSection = page.getByTestId('review-list-page');
  }

  /** SPA 내부 navigation — Vite re-optimize 회피 (LabelingPage 주석 참조). */
  async goto() {
    await this.page.evaluate(() => {
      window.history.pushState({}, '', '/review');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await this.page.waitForLoadState('networkidle').catch(() => undefined);
  }

  /** "검수 시작 {cctvName}" aria-label 버튼 (검수자 전용). */
  startReviewButtonFor(cctvName: string | RegExp) {
    return this.page.getByRole('button', { name: new RegExp(`검수 시작.*${cctvName}`) });
  }
}
