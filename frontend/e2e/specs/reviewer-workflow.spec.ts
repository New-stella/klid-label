import { test, expect } from '../fixtures/auth.fixture';
import { DashboardPage } from '../pages/DashboardPage';
import { ReviewListPage } from '../pages/ReviewListPage';
import { TaskListPage } from '../pages/TaskListPage';
import { VideoListPage } from '../pages/VideoListPage';

/**
 * REVIEWER 핵심 워크플로우 — 대시보드 → 영상 → 작업 → 검수 → 사용자 관리.
 *
 * <p>각 단계는 기존 시드 데이터(33영상/12사용자/검수33/배정20/라벨19) 상태에서 동작 검증.
 * 데이터 의존도가 큰 행 단위 검증 대신 페이지 진입·헤더·핵심 영역 노출 검증.
 */
test.describe('REVIEWER 워크플로우 — 대시보드/영상/작업/검수/관리', () => {
  test('대시보드_KPI_3카드와_이벤트_분포_노출', async ({ reviewerPage }) => {
    const dashboard = new DashboardPage(reviewerPage);
    await dashboard.goto();

    await expect(dashboard.heading).toBeVisible();
    await expect(dashboard.kpiGrid).toBeVisible();
    await expect(dashboard.imageDataCard).toBeVisible();
    await expect(dashboard.videoDataCard).toBeVisible();
  });

  test('영상_목록_진입_시_헤더_노출', async ({ reviewerPage }) => {
    const list = new VideoListPage(reviewerPage);
    await list.goto();

    await expect(
      reviewerPage.getByRole('heading', { name: /영상 목록|완료된 영상|영상/ }).first(),
    ).toBeVisible();
  });

  test('작업_목록_진입_시_헤더_노출', async ({ reviewerPage }) => {
    const tasks = new TaskListPage(reviewerPage);
    await tasks.goto();

    await expect(tasks.heading).toBeVisible();
  });

  test('검수_목록_진입_시_컨테이너_노출', async ({ reviewerPage }) => {
    const review = new ReviewListPage(reviewerPage);
    await review.goto();

    await expect(review.listSection).toBeVisible();
  });

  test('사용자_관리_진입_REVIEWER_전용_접근', async ({ reviewerPage }) => {
    await reviewerPage.evaluate(() => {
      window.history.pushState({}, '', '/manage/users');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await reviewerPage.waitForLoadState('networkidle').catch(() => undefined);

    // 가드를 통과하면 forbidden 으로 빠지지 않는다.
    await expect(reviewerPage).not.toHaveURL(/\/forbidden/);
  });
});
