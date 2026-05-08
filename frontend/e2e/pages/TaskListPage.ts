import { type Locator, type Page } from '@playwright/test';

/** 작업 목록 (SCR-TASK-001) POM. */
export class TaskListPage {
  readonly page: Page;
  readonly heading: Locator;
  readonly bulkAssignBar: Locator;
  readonly nextPageBtn: Locator;
  readonly prevPageBtn: Locator;

  constructor(page: Page) {
    this.page = page;
    this.heading = page.getByRole('heading', { name: '작업 목록' });
    this.bulkAssignBar = page.getByTestId('bulk-assign-bar');
    this.nextPageBtn = page.getByLabel('다음 페이지');
    this.prevPageBtn = page.getByLabel('이전 페이지');
  }

  /** SPA 내부 navigation — Vite re-optimize 회피 (LabelingPage 주석 참조). */
  async goto() {
    await this.page.evaluate(() => {
      window.history.pushState({}, '', '/task');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await this.page.waitForLoadState('networkidle').catch(() => undefined);
  }
}
