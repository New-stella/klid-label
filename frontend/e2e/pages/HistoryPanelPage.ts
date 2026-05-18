import { type Locator, type Page } from '@playwright/test';

/**
 * 라벨링 화면 이력 패널(인라인) POM.
 * data-testid="history-toggle" 버튼으로 열고, inline-history-panel 안에서 커밋 선택 + 롤백.
 */
export class HistoryPanelPage {
  readonly page: Page;
  readonly historyToggle: Locator;
  readonly inlinePanel: Locator;
  readonly historyPanel: Locator;

  constructor(page: Page) {
    this.page = page;
    this.historyToggle = page.getByTestId('history-toggle');
    this.inlinePanel = page.getByTestId('inline-history-panel');
    this.historyPanel = page.getByTestId('history-panel');
  }

  async openPanel() {
    if ((await this.historyToggle.count()) > 0) {
      await this.historyToggle.click();
      await this.inlinePanel.waitFor({ state: 'visible', timeout: 5000 }).catch(() => undefined);
    }
  }

  /** 현재 최신 커밋이 아닌 이전 커밋 행을 선택하여 롤백 트리거 노출. */
  async selectOldestAvailableCommit() {
    const commitRows = this.page.getByRole('listitem').filter({ has: this.page.getByRole('code') });
    const count = await commitRows.count();
    if (count < 2) return;
    await commitRows.nth(count - 1).click();
  }

  rollbackTrigger(shortHash: string): Locator {
    return this.page.getByTestId(`rollback-trigger-${shortHash}`);
  }

  /** 롤백 트리거 버튼 중 첫 번째를 클릭. */
  async clickFirstRollbackTrigger() {
    const btn = this.page.getByRole('button', { name: /롤백/ }).first();
    if ((await btn.count()) > 0) {
      await btn.click();
    }
  }

  /** RollbackConfirmModal의 "롤백" 확정 버튼 클릭. 모달 안 버튼만 선택 (트리거 버튼과 중복 방지). */
  async confirmRollback() {
    // modal-backdrop 내부로 스코프 — 트리거 버튼과 strict mode 충돌 방지
    const modal = this.page.getByTestId('modal-backdrop');
    const modalVisible = await modal.isVisible().catch(() => false);
    const confirm = modalVisible
      ? modal.getByRole('button', { name: /^롤백$/ })
      : this.page.getByRole('button', { name: /^롤백$/ }).last();
    if ((await confirm.count()) > 0) {
      await confirm.click();
    }
  }
}
