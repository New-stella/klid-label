import { type Locator, type Page } from '@playwright/test';

/** 영상 목록 페이지 POM. */
export class VideoListPage {
  readonly page: Page;
  readonly searchInput: Locator;
  readonly searchButton: Locator;
  readonly table: Locator;

  constructor(page: Page) {
    this.page = page;
    // VideoFilters 의 placeholder "검색어 입력" — getByLabel('검색') 은 form 컨테이너 매칭이라 fill 불가.
    this.searchInput = page.getByPlaceholder('검색어 입력');
    this.searchButton = page.getByRole('button', { name: /조회|검색/ });
    this.table = page.getByRole('table');
  }

  /** SPA 내부 navigation — Vite re-optimize 회피 (LabelingPage 주석 참조). */
  async goto() {
    await this.page.evaluate(() => {
      window.history.pushState({}, '', '/video/completed');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await this.page.waitForLoadState('networkidle').catch(() => undefined);
  }

  async search(keyword: string) {
    await this.searchInput.fill(keyword);
    await this.searchButton.click();
  }

  async clickRow(label: string | RegExp) {
    await this.page.getByRole('row', { name: label }).first().click();
  }
}
