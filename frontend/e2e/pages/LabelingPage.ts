import { type Locator, type Page } from '@playwright/test';

/** 라벨링 캔버스 페이지 POM. */
export class LabelingPage {
  readonly page: Page;
  readonly canvas: Locator;
  readonly bboxToolBtn: Locator;
  readonly saveBtn: Locator;

  constructor(page: Page) {
    this.page = page;
    this.canvas = page.getByTestId('canvas-shell');
    this.bboxToolBtn = page.locator('[aria-label="바운딩박스"]');
    this.saveBtn = page.getByTestId('label-header-save');
  }

  /**
   * SPA 내부 navigation 으로 라벨링 화면 진입.
   *
   * <p>Vite dev server 의 dependency re-optimization 으로 인해 새로 {@code page.goto()} 를 호출하면
   * 동적 import 체인 중 axios 등의 청크가 invalidate 되어 chrome-error 로 떨어지는 문제 회피.
   * History API 로 SPA 라우터를 직접 트리거한다.
   */
  async goto(videoId: number | string) {
    // path 화이트리스트 (videoId 는 number 만 허용 — XSS/Path injection 방어).
    const id = Number(videoId);
    if (!Number.isFinite(id) || id <= 0) {
      throw new Error(`invalid videoId: ${videoId}`);
    }
    await this.page.evaluate((target) => {
      window.history.pushState({}, '', target);
      window.dispatchEvent(new PopStateEvent('popstate'));
    }, `/label/${id}`);
    // 라벨링 페이지 렌더링 → 프레임 데이터 로드 → 캔버스 노출까지 대기.
    await this.page
      .getByTestId('labeling-page')
      .waitFor({ state: 'visible', timeout: 10000 })
      .catch(() => undefined);
    // 캔버스는 프레임 API 응답 후 렌더링되므로 추가 대기.
    await this.page
      .getByTestId('canvas-shell')
      .waitFor({ state: 'visible', timeout: 10000 })
      .catch(() => undefined);
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
