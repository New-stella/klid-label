import { type Locator, type Page } from '@playwright/test';

/** 라벨링 캔버스 페이지 POM. */
export class LabelingPage {
  readonly page: Page;
  readonly canvas: Locator;
  readonly bboxToolBtn: Locator;
  readonly saveBtn: Locator;

  // FrameDescriptionPanel (프레임 설명 입력 패널) — 우측 패널 하단.
  readonly frameDescriptionTextarea: Locator;
  readonly saveFrameDescriptionBtn: Locator;
  readonly frameDescriptionError: Locator;

  constructor(page: Page) {
    this.page = page;
    this.canvas = page.getByTestId('canvas-shell');
    this.bboxToolBtn = page.locator('[aria-label="바운딩박스"]');
    this.saveBtn = page.getByTestId('label-header-save');

    // textarea 는 <label htmlFor> + aria-label 로 접근성 이름 연결 (getByLabel 우선).
    this.frameDescriptionTextarea = page.getByLabel('프레임 설명 입력', { exact: true });
    // 저장 버튼은 textarea 직속 부모 div 안의 유일한 버튼 — 헤더/툴바의 '저장' 버튼과 구분하기 위해
    // 패널 범위로 스코프한다 (헤더·툴바 '저장' 버튼은 accessible name 이 동일해 role 만으론 구별 불가).
    this.saveFrameDescriptionBtn = this.frameDescriptionTextarea.locator('..').getByRole('button');
    // 저장 실패 시 role="alert" 문구.
    this.frameDescriptionError = page
      .getByRole('alert')
      .filter({ hasText: '설명 저장에 실패' });
  }

  /** 현재 프레임 설명 textarea 값. */
  async getFrameDescriptionValue(): Promise<string> {
    return this.frameDescriptionTextarea.inputValue();
  }

  /** 프레임 설명 입력 (기존 값 대체). */
  async fillFrameDescription(text: string) {
    await this.frameDescriptionTextarea.fill(text);
  }

  /** 프레임 설명 저장 버튼 클릭. */
  async saveFrameDescription() {
    await this.saveFrameDescriptionBtn.click();
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
