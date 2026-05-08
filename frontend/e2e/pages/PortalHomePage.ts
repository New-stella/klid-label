import { type Locator, type Page } from '@playwright/test';

/** 포털 홈 페이지 POM (TUS 업로드 dropzone). */
export class PortalHomePage {
  readonly page: Page;
  readonly dropzone: Locator;
  readonly fileInput: Locator;

  constructor(page: Page) {
    this.page = page;
    this.dropzone = page.getByTestId('upload-dropzone');
    this.fileInput = page.locator('input[type="file"]');
  }

  /** SPA 내부 navigation — Vite re-optimize 회피. */
  async goto() {
    await this.page.evaluate(() => {
      if (window.location.pathname !== '/portal') {
        window.history.pushState({}, '', '/portal');
        window.dispatchEvent(new PopStateEvent('popstate'));
      }
    });
    await this.page.waitForLoadState('networkidle').catch(() => undefined);
  }

  async uploadFile(filePath: string) {
    await this.fileInput.setInputFiles(filePath);
  }
}
