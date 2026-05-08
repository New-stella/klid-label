import { test, expect } from '../fixtures/auth.fixture';
import { ReviewPage } from '../pages/ReviewPage';

test.describe.serial('검수 플로우 (REVIEWER)', () => {
  test('검수_대기_목록_진입', async ({ reviewerPage }) => {
    const review = new ReviewPage(reviewerPage);
    await review.gotoList();
    // 검수 목록 컨테이너 식별 — heading 미존재로 testid 사용.
    await expect(reviewerPage.getByTestId('review-list-page')).toBeVisible();
  });

  test('검수_시작_및_승인', async ({ reviewerPage }) => {
    const review = new ReviewPage(reviewerPage);
    await review.gotoDetail(1);

    // 시작 버튼 존재 시 시작 → 승인
    if ((await review.startBtn.count()) > 0) {
      await review.start();
    }

    if ((await review.approveBtn.count()) > 0) {
      await review.approve();
      await expect(reviewerPage.getByText(/승인|완료/).first()).toBeVisible({ timeout: 5000 });
    }
  });
});
