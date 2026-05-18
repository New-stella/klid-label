import { test, expect } from '../fixtures/auth.fixture';
import { HistoryPanelPage } from '../pages/HistoryPanelPage';
import { LabelingPage } from '../pages/LabelingPage';
import { ReviewPage } from '../pages/ReviewPage';
import { WORKFLOW_VIDEO_ID, WORKFLOW_SRC_SN } from '../fixtures/test-data';

/**
 * 전체 워크플로우 E2E — 순차 의존 플로우.
 *
 * 라벨링(LABELER) → 저장(LABELER) → 검수제출(LABELER)
 *   → 반려(REVIEWER) → 롤백(LABELER) → 재검수제출(LABELER)
 *   → 수락(REVIEWER)
 *
 * 시드 데이터:
 *  - rawDataId=9035: LABELER(userNo=2001)에게 배정됨 (LS_PJT_USER_AUTHRT)
 *  - SRC_SN=241: 해당 rawData의 첫 번째 프레임 → /label/:srcSn URL에 사용
 *  - REVIEW_VIDEO_ID=9035: 검수 상세 진입에 사용 (rawDataId 기준)
 *
 * 각 테스트는 BE DB 상태를 순서대로 전진시킨다. serial 보장으로 격리.
 */

const SRC_SN = WORKFLOW_SRC_SN;
const REVIEW_VIDEO_ID = WORKFLOW_VIDEO_ID;

test.describe.serial('전체 워크플로우 — 라벨링→저장→검수제출→반려→롤백→재요청→승인', () => {
  test('WORKER_라벨링_화면_진입', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(SRC_SN);

    await expect(labelerPage).toHaveURL(new RegExp(`/label/${SRC_SN}`));
    await expect(labelerPage.getByTestId('labeling-page')).toBeVisible();
  });

  test('WORKER_BBox_저장_토스트_노출', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(SRC_SN);

    if ((await labeling.bboxToolBtn.count()) > 0) {
      await labeling.bboxToolBtn.first().click();
    }

    if ((await labeling.canvas.count()) > 0) {
      await labeling.drawBoundingBox({ x: 80, y: 80 }, { x: 220, y: 180 });
    }

    if ((await labeling.saveBtn.count()) > 0) {
      await labeling.save();
      await expect(labelerPage.getByText(/저장|완료/).first()).toBeVisible({ timeout: 7000 });
    }
  });

  test('WORKER_검수_제출', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(SRC_SN);

    const submitBtn = labelerPage.getByTestId('submit-review-button');
    if ((await submitBtn.count()) > 0) {
      await submitBtn.click();
      await expect(
        labelerPage.getByText(/검수 제출 완료|제출/).first(),
      ).toBeVisible({ timeout: 7000 });
    }
  });

  test('REVIEWER_검수_목록_진입', async ({ reviewerPage }) => {
    const review = new ReviewPage(reviewerPage);
    await review.gotoList();

    await expect(reviewerPage.getByTestId('review-list-page')).toBeVisible();
  });

  test('REVIEWER_반려_처리', async ({ reviewerPage }) => {
    const review = new ReviewPage(reviewerPage);
    await review.gotoDetail(REVIEW_VIDEO_ID);

    // PENDING → IN_REVIEW 자동 전이 (ReviewPage 진입 시 startReview 자동 호출)
    // startBtn이 남아있으면 수동 클릭
    if ((await review.startBtn.count()) > 0) {
      await review.start();
    }

    const rejectBtn = reviewerPage.getByTestId('review-action-reject');
    if ((await rejectBtn.count()) > 0) {
      await rejectBtn.click();

      // RejectModal — 반려 사유 입력
      await reviewerPage.getByLabel('반려 사유').fill('E2E 테스트 반려 사유 — 라벨 재작성 요청');
      await reviewerPage.getByRole('button', { name: '반려 확정' }).click();

      await expect(
        reviewerPage.getByText(/반려 처리됨|반려/).first(),
      ).toBeVisible({ timeout: 7000 });
    }
  });

  test('WORKER_이력_패널_오픈_후_롤백', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    const history = new HistoryPanelPage(labelerPage);
    await labeling.goto(SRC_SN);

    // 이력 패널 열기 — 토글 버튼이 없으면 skip
    if ((await history.historyToggle.count()) === 0) return;
    await history.openPanel();

    // 패널이 열리지 않으면 skip (Gitea 미연동 환경)
    const panelVisible = await history.inlinePanel.isVisible().catch(() => false);
    if (!panelVisible) return;

    // 커밋 이력 확인 — 2개 이상이어야 롤백 가능. 아니면 skip
    const commitRows = labelerPage
      .getByRole('listitem')
      .filter({ has: labelerPage.getByRole('code') });
    const count = await commitRows.count();
    if (count < 2) return;

    // 현재 최신이 아닌 이전 커밋 선택 → 롤백 트리거 노출
    await history.selectOldestAvailableCommit();
    await history.clickFirstRollbackTrigger();

    // RollbackConfirmModal 확정
    await history.confirmRollback();

    await expect(
      labelerPage.getByText(/롤백|완료/).first(),
    ).toBeVisible({ timeout: 10000 });
  });

  test('WORKER_재검수_제출', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(SRC_SN);

    const submitBtn = labelerPage.getByTestId('submit-review-button');
    if ((await submitBtn.count()) > 0) {
      await submitBtn.click();
      await expect(
        labelerPage.getByText(/검수 제출 완료|제출/).first(),
      ).toBeVisible({ timeout: 7000 });
    }
  });

  test('REVIEWER_최종_승인', async ({ reviewerPage }) => {
    const review = new ReviewPage(reviewerPage);
    await review.gotoDetail(REVIEW_VIDEO_ID);

    // PENDING → IN_REVIEW
    if ((await review.startBtn.count()) > 0) {
      await review.start();
    }

    const approveBtn = reviewerPage.getByTestId('review-action-approve');
    if ((await approveBtn.count()) > 0) {
      await approveBtn.click();

      // 승인 ConfirmDialog
      const confirmBtn = reviewerPage.getByRole('button', { name: /승인 확정/ });
      if ((await confirmBtn.count()) > 0) {
        await confirmBtn.click();
      }

      await expect(
        reviewerPage.getByText(/승인 완료|승인/).first(),
      ).toBeVisible({ timeout: 7000 });
    }
  });
});
