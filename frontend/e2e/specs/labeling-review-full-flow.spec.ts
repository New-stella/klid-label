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
 * DB 전제조건:
 *  - LS_RAW_DATA_STATUS.DATA_STTS_CD = 'ASSIGNED' for RAW_DATA_ID=9035
 *  - LS_LABEL_VERSION에 2개 이상의 버전이 존재 (롤백 테스트용)
 *  - LS_TASK_ASSIGNMENT: LABELER(2001), REVIEWER(1001) 배정됨
 *
 * 각 테스트는 DB 상태를 순서대로 전진시킨다. serial 보장으로 격리.
 */

const SRC_SN = WORKFLOW_SRC_SN;       // 241
const REVIEW_VIDEO_ID = WORKFLOW_VIDEO_ID; // 9035

test.describe.serial('전체 워크플로우 — 라벨링→저장→검수제출→반려→롤백→재요청→승인', () => {
  test('WORKER_라벨링_화면_진입', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(SRC_SN);

    await expect(labelerPage).toHaveURL(new RegExp(`/label/${SRC_SN}`));
    await expect(labelerPage.getByTestId('labeling-page')).toBeVisible();
    await expect(labelerPage.getByTestId('canvas-shell')).toBeVisible();
  });

  test('WORKER_BBox_그리고_저장', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(SRC_SN);

    // BBox 도구 선택
    await expect(labeling.bboxToolBtn).toBeVisible({ timeout: 5000 });
    await labeling.bboxToolBtn.first().click();

    // 캔버스에 BBox 드로잉
    await expect(labeling.canvas).toBeVisible({ timeout: 5000 });
    await labeling.drawBoundingBox({ x: 80, y: 80 }, { x: 220, y: 180 });

    // 저장 — API 응답 + 토스트 검증
    const saveResponse = labelerPage.waitForResponse(
      (res) => res.url().includes(`/frames/${SRC_SN}/labels`) && res.request().method() === 'PUT',
    );
    await labeling.save();
    const res = await saveResponse;
    expect(res.status()).toBe(200);

    await expect(labelerPage.getByText('저장됨 · 버전 기록됨')).toBeVisible({ timeout: 10000 });
  });

  test('WORKER_검수_제출', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(SRC_SN);

    const submitBtn = labelerPage.getByTestId('submit-review-button');
    await expect(submitBtn).toBeVisible({ timeout: 5000 });

    // 검수 제출 — API 응답 + 토스트 검증
    const submitResponse = labelerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${REVIEW_VIDEO_ID}/submit`) &&
        res.request().method() === 'POST',
    );
    await submitBtn.click();
    const res = await submitResponse;
    expect(res.status()).toBe(200);

    await expect(labelerPage.getByText('검수 제출 완료')).toBeVisible({ timeout: 7000 });
  });

  test('REVIEWER_검수_목록_진입', async ({ reviewerPage }) => {
    const review = new ReviewPage(reviewerPage);
    await review.gotoList();

    await expect(reviewerPage.getByTestId('review-list-page')).toBeVisible({ timeout: 10000 });
  });

  test('REVIEWER_반려_처리', async ({ reviewerPage }) => {
    const review = new ReviewPage(reviewerPage);

    // PENDING → IN_REVIEW 자동 전이 waitForResponse로 확인
    const startResponse = reviewerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${REVIEW_VIDEO_ID}/start`) &&
        res.request().method() === 'POST',
    );
    await review.gotoDetail(REVIEW_VIDEO_ID);
    await startResponse;

    // 검수 페이지 로드 대기
    await expect(reviewerPage.getByTestId('review-page')).toBeVisible({ timeout: 10000 });

    // 반려 버튼 클릭
    const rejectBtn = reviewerPage.getByTestId('review-action-reject');
    await expect(rejectBtn).toBeVisible({ timeout: 5000 });
    await rejectBtn.click();

    // 반려 사유 입력 후 확정
    await reviewerPage.getByLabel('반려 사유').fill('E2E 테스트 반려 사유 — 라벨 재작성 요청');

    const rejectResponse = reviewerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${REVIEW_VIDEO_ID}/reject`) &&
        res.request().method() === 'POST',
    );
    await reviewerPage.getByRole('button', { name: '반려 확정' }).click();
    const res = await rejectResponse;
    expect(res.status()).toBe(200);

    await expect(reviewerPage.getByText('반려 처리됨')).toBeVisible({ timeout: 7000 });
  });

  test('WORKER_이력_패널_오픈_후_롤백', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    const history = new HistoryPanelPage(labelerPage);
    await labeling.goto(SRC_SN);

    // 이력 패널 열기
    await expect(history.historyToggle).toBeVisible({ timeout: 5000 });
    await history.openPanel();
    await expect(history.inlinePanel).toBeVisible({ timeout: 5000 });

    // 커밋 이력 2개 이상 확인 (이전 테스트 실행 누적 허용)
    const commitRows = labelerPage
      .getByRole('listitem')
      .filter({ has: labelerPage.getByRole('code') });
    // 비동기 버전 목록이 로드될 때까지 대기
    await expect(commitRows.first()).toBeVisible({ timeout: 8000 });
    const commitCount = await commitRows.count();
    expect(commitCount).toBeGreaterThanOrEqual(2);

    // 가장 오래된 커밋 선택 → 롤백 트리거 노출
    await history.selectOldestAvailableCommit();
    await history.clickFirstRollbackTrigger();

    // 롤백 확정 — API 응답 검증
    const rollbackResponse = labelerPage.waitForResponse(
      (res) =>
        res.url().includes('/versions/') &&
        res.url().includes('/rollback') &&
        res.request().method() === 'POST',
    );
    await history.confirmRollback();
    const res = await rollbackResponse;
    expect(res.status()).toBe(200);
  });

  test('WORKER_재검수_제출', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(SRC_SN);

    const submitBtn = labelerPage.getByTestId('submit-review-button');
    await expect(submitBtn).toBeVisible({ timeout: 5000 });

    const submitResponse = labelerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${REVIEW_VIDEO_ID}/submit`) &&
        res.request().method() === 'POST',
    );
    await submitBtn.click();
    const res = await submitResponse;
    expect(res.status()).toBe(200);

    await expect(labelerPage.getByText('검수 제출 완료')).toBeVisible({ timeout: 7000 });
  });

  test('REVIEWER_최종_승인', async ({ reviewerPage }) => {
    const review = new ReviewPage(reviewerPage);

    // PENDING → IN_REVIEW 자동 전이
    const startResponse = reviewerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${REVIEW_VIDEO_ID}/start`) &&
        res.request().method() === 'POST',
    );
    await review.gotoDetail(REVIEW_VIDEO_ID);
    await startResponse;

    await expect(reviewerPage.getByTestId('review-page')).toBeVisible({ timeout: 10000 });

    // 승인 버튼 클릭
    const approveBtn = reviewerPage.getByTestId('review-action-approve');
    await expect(approveBtn).toBeVisible({ timeout: 5000 });

    const approveResponse = reviewerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${REVIEW_VIDEO_ID}/approve`) &&
        res.request().method() === 'POST',
    );
    await approveBtn.click();

    // 승인 확정 다이얼로그
    const confirmBtn = reviewerPage.getByRole('button', { name: /승인 확정/ });
    if ((await confirmBtn.count()) > 0) {
      await confirmBtn.click();
    }

    const res = await approveResponse;
    expect(res.status()).toBe(200);

    await expect(reviewerPage.getByText('승인 완료')).toBeVisible({ timeout: 7000 });
  });
});
