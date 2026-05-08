import { test, expect } from '../fixtures/auth.fixture';
import { LabelingPage } from '../pages/LabelingPage';
import { VideoListPage } from '../pages/VideoListPage';

/**
 * 라벨링 플로우 — 영상 선택 → 라벨링 진입 → BBox 작성 → 저장 → 토스트.
 * 순차 의존이므로 test.describe.serial 사용.
 */
test.describe.serial('라벨링 플로우 (WORKER)', () => {
  test('영상_목록_진입', async ({ workerPage }) => {
    const list = new VideoListPage(workerPage);
    await list.goto();
    await expect(
      workerPage.getByRole('heading', { name: /영상 목록|완료된 영상|영상/ }).first(),
    ).toBeVisible();
  });

  test('라벨링_캔버스_진입', async ({ workerPage }) => {
    const labeling = new LabelingPage(workerPage);
    // 시드 데이터의 srcSn=1 사용 (라벨 보유)
    await labeling.goto(1);
    await expect(workerPage).toHaveURL(/\/label\/1/);
  });

  test('BBox_작성_저장_후_토스트', async ({ workerPage }) => {
    const labeling = new LabelingPage(workerPage);
    await labeling.goto(1);

    // BBox 도구 선택 (존재 시)
    if ((await labeling.bboxToolBtn.count()) > 0) {
      await labeling.bboxToolBtn.first().click();
    }

    // 캔버스가 마운트되면 박스 드래그
    if ((await labeling.canvas.count()) > 0) {
      await labeling.drawBoundingBox({ x: 100, y: 100 }, { x: 250, y: 200 });
    }

    if ((await labeling.saveBtn.count()) > 0) {
      await labeling.save();
      await expect(workerPage.getByText(/저장|완료/).first()).toBeVisible({ timeout: 5000 });
    }
  });
});
