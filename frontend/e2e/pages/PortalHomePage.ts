import { type Locator, type Page } from '@playwright/test';

/** 포털 홈 페이지 POM (TUS 업로드 dropzone). */
export class PortalHomePage {
  readonly page: Page;
  readonly dropzone: Locator;
  readonly fileInput: Locator;

  constructor(page: Page) {
    this.page = page;
    this.dropzone = page.getByTestId('portal-dropzone');
    this.fileInput = page.locator('input[type="file"]');
  }

  async goto() {
    await this.page.goto('/portal');
  }

  async uploadFile(filePath: string) {
    await this.fileInput.setInputFiles(filePath);
  }
}
