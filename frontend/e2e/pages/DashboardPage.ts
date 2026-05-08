import { type Locator, type Page } from '@playwright/test';

/** 메인 대시보드 (SCR-DASH-001) POM. */
export class DashboardPage {
  readonly page: Page;
  readonly heading: Locator;
  readonly kpiGrid: Locator;
  readonly imageDataCard: Locator;
  readonly videoDataCard: Locator;

  constructor(page: Page) {
    this.page = page;
    this.heading = page.getByRole('heading', { name: '대시보드' });
    this.kpiGrid = page.getByTestId('dashboard-kpi-grid');
    // 이벤트 6 종 분포는 이미지/영상 데이터 카드 내부 inline 그리드 — 카드 헤더로 식별.
    this.imageDataCard = page.getByText('이미지 데이터 개수').first();
    this.videoDataCard = page.getByText('영상 데이터 개수').first();
  }

  /** SPA 내부 navigation — Vite re-optimize 회피 (LabelingPage 주석 참조). */
  async goto() {
    await this.page.evaluate(() => {
      if (window.location.pathname !== '/dashboard') {
        window.history.pushState({}, '', '/dashboard');
        window.dispatchEvent(new PopStateEvent('popstate'));
      }
    });
    await this.page.waitForLoadState('networkidle').catch(() => undefined);
  }
}
