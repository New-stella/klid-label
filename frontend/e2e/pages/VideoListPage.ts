import { type Locator, type Page } from '@playwright/test';

/** 영상 목록 페이지 POM. */
export class VideoListPage {
  readonly page: Page;
  readonly searchInput: Locator;
  readonly searchButton: Locator;
  readonly table: Locator;

  constructor(page: Page) {
    this.page = page;
    this.searchInput = page.getByLabel('검색');
    this.searchButton = page.getByRole('button', { name: '검색' });
    this.table = page.getByRole('table');
  }

  async goto() {
    await this.page.goto('/video/completed');
  }

  async search(keyword: string) {
    await this.searchInput.fill(keyword);
    await this.searchButton.click();
  }

  async clickRow(label: string | RegExp) {
    await this.page.getByRole('row', { name: label }).first().click();
  }
}
