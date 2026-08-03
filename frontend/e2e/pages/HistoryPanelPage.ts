import { type Locator, type Page } from '@playwright/test';

/**
 * 라벨링 화면 이력 패널(인라인) POM.
 *
 * <p>패널은 두 탭으로 나뉜다 — 기본 활성 탭은 "변경 이력"(저장 이벤트, LS_DATA_LBL_HSTRY)이고
 * **커밋 목록·diff·롤백은 "버전" 탭**(LS_LABEL_VERSION)에 있다. 롤백을 다루려면 반드시
 * {@link openVersionsTab} 으로 전환해야 한다.
 */
export class HistoryPanelPage {
  readonly page: Page;
  readonly historyToggle: Locator;
  readonly inlinePanel: Locator;
  readonly historyPanel: Locator;
  readonly versionsTab: Locator;
  readonly versionsPanel: Locator;
  /** 커밋 목록 행 — `commit-row-{shortHash}` testid 접두로 식별. */
  readonly commitRows: Locator;

  constructor(page: Page) {
    this.page = page;
    this.historyToggle = page.getByTestId('history-toggle');
    this.inlinePanel = page.getByTestId('inline-history-panel');
    this.historyPanel = page.getByTestId('history-panel');
    this.versionsTab = page.getByTestId('history-tab-versions');
    this.versionsPanel = page.getByTestId('history-versions-panel');
    this.commitRows = page.locator('[data-testid^="commit-row-"]');
  }

  async openPanel() {
    if ((await this.historyToggle.count()) > 0) {
      await this.historyToggle.click();
      await this.inlinePanel.waitFor({ state: 'visible', timeout: 5000 }).catch(() => undefined);
    }
  }

  /** 커밋(버전) 탭으로 전환. 기본 탭은 "변경 이력"이라 롤백 전 반드시 호출한다. */
  async openVersionsTab() {
    await this.versionsTab.click();
    await this.versionsPanel.waitFor({ state: 'visible', timeout: 5000 });
  }

  /**
   * 최신이 아닌 직전 커밋(index 1)을 선택해 롤백 트리거를 노출시킨다.
   * index 0 은 현재 HEAD 라 롤백 대상이 될 수 없다(FE 가 트리거를 렌더하지 않는다).
   */
  async selectRollbackTargetCommit() {
    await this.commitRows.nth(1).getByRole('button').click();
  }

  rollbackTrigger(shortHash: string): Locator {
    return this.page.getByTestId(`rollback-trigger-${shortHash}`);
  }

  /** 롤백 트리거 버튼(`rollback-trigger-*`) 클릭. */
  async clickRollbackTrigger() {
    await this.page.locator('[data-testid^="rollback-trigger-"]').first().click();
  }

  /** RollbackConfirmModal 의 "롤백" 확정 버튼 클릭 — 트리거와 이름이 같아 모달 내부로 스코프한다. */
  async confirmRollback() {
    const modal = this.page.getByTestId('modal-backdrop');
    await modal.waitFor({ state: 'visible', timeout: 5000 });
    await modal.getByRole('button', { name: /^롤백$/ }).click();
  }
}
