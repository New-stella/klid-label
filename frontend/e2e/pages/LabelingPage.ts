import { type Locator, type Page } from '@playwright/test';

/** 라벨링 캔버스 페이지 POM. */
export class LabelingPage {
  readonly page: Page;
  readonly canvas: Locator;
  readonly bboxToolBtn: Locator;
  readonly saveBtn: Locator;

  constructor(page: Page) {
    this.page = page;
    this.canvas = page.getByTestId('labeling-canvas');
    this.bboxToolBtn = page.getByRole('button', { name: /바운딩박스|BBox/i });
    this.saveBtn = page.getByRole('button', { name: /저장/ });
  }

  async goto(videoId: number | string) {
    await this.page.goto(`/label/${videoId}`);
  }

  /** 캔버스에 바운딩박스 드래그. */
  async drawBoundingBox(start: { x: number; y: number }, end: { x: number; y: number }) {
    const box = await this.canvas.boundingBox();
    if (!box) throw new Error('canvas not visible');
    await this.page.mouse.move(box.x + start.x, box.y + start.y);
    await this.page.mouse.down();
    await this.page.mouse.move(box.x + end.x, box.y + end.y, { steps: 10 });
    await this.page.mouse.up();
  }

  async save() {
    await this.saveBtn.click();
  }
}
