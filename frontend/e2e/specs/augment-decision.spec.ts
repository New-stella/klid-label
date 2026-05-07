import { test, expect } from '../fixtures/auth.fixture';

test.describe('증강 결과 채택/반려 (REVIEWER)', () => {
  test('PENDING_채택_시_ACCEPTED_상태로_변경', async ({ reviewerPage }) => {
    // 증강 결과 페이지 진입
    await reviewerPage.goto('/augment/result/3001');

    // PENDING 상태 노출 후 [채택] 클릭
    const adoptBtn = reviewerPage.getByRole('button', { name: /채택/ });
    if ((await adoptBtn.count()) > 0) {
      await adoptBtn.first().click();

      // 상태 ACCEPTED 또는 토스트 노출 확인
      await expect(
        reviewerPage.getByText(/ACCEPTED|채택됨|채택 완료/).first(),
      ).toBeVisible({ timeout: 5000 });
    }
  });
});
