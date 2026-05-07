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

  async gotoList() {
    await this.page.goto('/review');
  }

  async gotoDetail(reviewId: number | string) {
    await this.page.goto(`/review/${reviewId}`);
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
