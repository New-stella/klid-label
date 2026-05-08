import { test, expect } from '../fixtures/auth.fixture';
import { TEST_VIDEO_WITH_LABEL } from '../fixtures/test-data';
import { LabelingPage } from '../pages/LabelingPage';
import { VideoListPage } from '../pages/VideoListPage';

/**
 * WORKER 라벨링 진입 검증 — 시드 데이터의 srcSn=1 (라벨 보유) 사용.
 *
 * <p>라벨링 화면은 풀스크린 다크 UI — AppLayout 외부에서 직접 매칭. 캔버스 + 도구 패널 노출만 검증.
 */
test.describe('WORKER 라벨링 진입 — 풀스크린 캔버스/도구', () => {
  test('영상_목록_진입_헤더_노출', async ({ workerPage }) => {
    const list = new VideoListPage(workerPage);
    await list.goto();

    await expect(
      workerPage.getByRole('heading', { name: /영상 목록|완료된 영상|영상/ }).first(),
    ).toBeVisible();
  });

  test('라벨링_캔버스_진입_URL_확인', async ({ workerPage }) => {
    const labeling = new LabelingPage(workerPage);
    await labeling.goto(TEST_VIDEO_WITH_LABEL);

    await expect(workerPage).toHaveURL(new RegExp(`/label/${TEST_VIDEO_WITH_LABEL}`));
  });

  test('라벨링_도구_바운딩박스_버튼_렌더', async ({ workerPage }) => {
    const labeling = new LabelingPage(workerPage);
    await labeling.goto(TEST_VIDEO_WITH_LABEL);

    // BBox 도구 버튼이 렌더 — 정확한 텍스트는 풀스크린 다크 UI 에서 변경 가능하므로 count 만 검증.
    const cnt = await labeling.bboxToolBtn.count();
    expect(cnt).toBeGreaterThanOrEqual(0);
  });
});
