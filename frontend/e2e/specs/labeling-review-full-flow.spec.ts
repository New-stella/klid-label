import { test, expect } from '../fixtures/auth.fixture';
import { beCall, issueDevToken, withApiContext } from '../fixtures/be-client';
import {
  resolveWorkflowFixture,
  TEST_USERS,
  type WorkflowFixture,
} from '../fixtures/test-data';
import { HistoryPanelPage } from '../pages/HistoryPanelPage';
import { LabelingPage } from '../pages/LabelingPage';
import { ReviewPage } from '../pages/ReviewPage';

/**
 * TC-E2E-016~019 전체 워크플로우 E2E — 순차 의존 플로우.
 *
 * 라벨링(WORKER) → 저장(WORKER) → 검수제출(WORKER)
 *   → 반려(REVIEWER) → 롤백(WORKER) → 재검수제출(WORKER)
 *   → 승인(REVIEWER)
 *
 * <p><b>대상 영상/프레임은 하드코딩하지 않는다</b> — {@link resolveWorkflowFixture} 가 실행 시점에
 * 공개 API 로 (배정된 영상, 첫 프레임) 을 해석하고 워크플로 사전조건(제출 가능 상태, 롤백용 커밋 2건)
 * 까지 갖춘다. 구 상수(rawSn=9035 / srcSn=241)는 현 스택에 존재하지 않아 첫 단계에서 죽었고
 * serial 이라 이후 전 구간이 스킵됐다(H-ISSUE-143).
 *
 * <p>각 테스트는 DB 상태를 순서대로 전진시킨다. serial 로 격리.
 */

let fixture: WorkflowFixture;

// Playwright 는 훅 첫 인자를 반드시 객체 구조분해로 받도록 강제한다(이름 있는 인자는 런타임 에러).
// TestInfo 는 두 번째 인자라 빈 패턴을 피할 수 없다.
// eslint-disable-next-line no-empty-pattern
test.beforeAll(async ({}, testInfo) => {
  // 픽스처 해석은 승인 사이클(커밋 적층)을 포함할 수 있어 기본 테스트 타임아웃으로는 부족하다.
  testInfo.setTimeout(180_000);
  fixture = await resolveWorkflowFixture();
});

/** 현재 검수 상태(LS_RAW_DATA_STATUS.DATA_STTS_CD)를 BE 에서 직접 읽는다. */
async function readStatus(): Promise<string | undefined> {
  const token = await issueDevToken(TEST_USERS.reviewer);
  return withApiContext(async (ctx) => {
    const res = await beCall<{ dataSttsCd?: string }>(
      ctx,
      'get',
      `/v1/reviews/${fixture.videoId}`,
      token,
    );
    return res.data?.dataSttsCd;
  });
}

test.describe.serial('전체 워크플로우 — 라벨링→저장→검수제출→반려→롤백→재제출→승인', () => {
  test('E2E_전체워크플로_픽스처는_실행시점에_유효한_rawSn을_참조한다', async () => {
    // given: 해석된 픽스처
    expect(fixture.videoId).toBeGreaterThan(0);
    expect(fixture.srcSn).toBeGreaterThan(0);

    // when / then: 해석된 식별자가 실제로 BE 에 존재하고 접근 가능해야 한다.
    const workerToken = await issueDevToken(TEST_USERS.labeler);
    await withApiContext(async (ctx) => {
      const review = await beCall<{ dataSttsCd?: string }>(
        ctx,
        'get',
        `/v1/reviews/${fixture.videoId}`,
        workerToken,
      );
      expect(review.status).toBe(200);
      expect(['ASSIGNED', 'REJECTED', 'APPROVED']).toContain(review.data?.dataSttsCd);

      const labels = await beCall(ctx, 'get', `/v1/frames/${fixture.srcSn}/labels`, workerToken);
      expect(labels.status).toBe(200);

      // 롤백 단계 사전조건 — 커밋 2건 이상(최신 + 롤백 대상).
      const versions = await beCall<unknown[]>(
        ctx,
        'get',
        `/v1/frames/${fixture.srcSn}/versions`,
        workerToken,
      );
      expect(versions.status).toBe(200);
      expect((versions.data ?? []).length).toBeGreaterThanOrEqual(2);
    });
  });

  test('WORKER_라벨링_화면_진입', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(fixture.srcSn);

    await expect(labelerPage).toHaveURL(new RegExp(`/label/${fixture.srcSn}`));
    await expect(labelerPage.getByTestId('labeling-page')).toBeVisible();
    await expect(labelerPage.getByTestId('canvas-shell')).toBeVisible();
  });

  test('WORKER_BBox_그리고_저장', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(fixture.srcSn);

    // given: BBox 도구 선택
    await expect(labeling.bboxToolBtn.first()).toBeVisible({ timeout: 5000 });
    await labeling.bboxToolBtn.first().click();

    // when: 캔버스에 BBox 드로잉 후 저장
    await expect(labeling.canvas).toBeVisible({ timeout: 5000 });
    await labeling.drawBoundingBox({ x: 80, y: 80 }, { x: 220, y: 180 });

    const saveResponse = labelerPage.waitForResponse(
      (res) =>
        res.url().includes(`/frames/${fixture.srcSn}/labels`) && res.request().method() === 'PUT',
    );
    await labeling.save();
    const res = await saveResponse;

    // then: 저장 API 200 + 성공 토스트.
    //   토스트 문구는 '저장됨' 이다 — 라벨 저장은 작업본 임시저장이라 버전 스냅샷을 만들지 않는다
    //   (구 기대문구 '저장됨 · 버전 기록됨' 은 FE 에서 제거됨).
    expect(res.status()).toBe(200);
    await expect(
      labelerPage.getByRole('alert').filter({ hasText: '저장됨' }).first(),
    ).toBeVisible({ timeout: 10000 });
  });

  test('WORKER_검수_제출', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(fixture.srcSn);

    const submitBtn = labelerPage.getByTestId('submit-review-button');
    await expect(submitBtn).toBeEnabled({ timeout: 10000 });

    const submitResponse = labelerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${fixture.videoId}/submit`) &&
        res.request().method() === 'POST',
    );
    await submitBtn.click();
    const res = await submitResponse;
    expect(res.status()).toBe(200);

    await expect(
      labelerPage.getByRole('alert').filter({ hasText: '검수 제출 완료' }).first(),
    ).toBeVisible({ timeout: 10000 });
  });

  test('REVIEWER_검수_목록_진입', async ({ reviewerPage }) => {
    const review = new ReviewPage(reviewerPage);
    await review.gotoList();

    await expect(reviewerPage.getByTestId('review-list-page')).toBeVisible({ timeout: 10000 });
  });

  test('REVIEWER_반려_처리', async ({ reviewerPage }) => {
    const review = new ReviewPage(reviewerPage);

    // given: 상세 진입 시 PENDING → IN_REVIEW 자동 전이
    const startResponse = reviewerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${fixture.videoId}/start`) &&
        res.request().method() === 'POST',
    );
    await review.gotoDetail(fixture.videoId);
    await startResponse;

    await expect(reviewerPage.getByTestId('review-page')).toBeVisible({ timeout: 10000 });

    // when: 반려 사유 입력 후 확정
    const rejectBtn = reviewerPage.getByTestId('review-action-reject');
    await expect(rejectBtn).toBeVisible({ timeout: 5000 });
    await rejectBtn.click();

    await reviewerPage.getByTestId('reject-reason-input').fill('E2E 테스트 반려 사유 — 라벨 재작성 요청');

    const rejectResponse = reviewerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${fixture.videoId}/reject`) &&
        res.request().method() === 'POST',
    );
    await reviewerPage.getByRole('button', { name: '반려 확정' }).click();
    const res = await rejectResponse;

    // then
    expect(res.status()).toBe(200);
    await expect(
      reviewerPage.getByRole('alert').filter({ hasText: '반려 처리됨' }).first(),
    ).toBeVisible({ timeout: 10000 });
  });

  test('WORKER_이력_패널_오픈_후_롤백', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    const history = new HistoryPanelPage(labelerPage);
    await labeling.goto(fixture.srcSn);

    // given: 이력 패널 → 버전(커밋) 탭. 기본 탭은 '변경 이력' 이라 커밋 목록이 없다.
    await expect(history.historyToggle).toBeVisible({ timeout: 5000 });
    await history.openPanel();
    await expect(history.inlinePanel).toBeVisible({ timeout: 5000 });
    await history.openVersionsTab();

    // 커밋 2건 이상 (최신 + 롤백 대상) — 픽스처가 사전에 보장한다.
    await expect(history.commitRows.first()).toBeVisible({ timeout: 10000 });
    expect(await history.commitRows.count()).toBeGreaterThanOrEqual(2);

    // when: 직전 커밋 선택 → 롤백 트리거 → 확정
    await history.selectRollbackTargetCommit();
    await history.clickRollbackTrigger();

    const rollbackResponse = labelerPage.waitForResponse(
      (res) =>
        res.url().includes('/versions/') &&
        res.url().includes('/rollback') &&
        res.request().method() === 'POST',
    );
    await history.confirmRollback();
    const res = await rollbackResponse;

    // then
    expect(res.status()).toBe(200);
  });

  test('WORKER_재검수_제출', async ({ labelerPage }) => {
    const labeling = new LabelingPage(labelerPage);
    await labeling.goto(fixture.srcSn);

    const submitBtn = labelerPage.getByTestId('submit-review-button');
    await expect(submitBtn).toBeEnabled({ timeout: 10000 });

    const submitResponse = labelerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${fixture.videoId}/submit`) &&
        res.request().method() === 'POST',
    );
    await submitBtn.click();
    const res = await submitResponse;
    expect(res.status()).toBe(200);

    await expect(
      labelerPage.getByRole('alert').filter({ hasText: '검수 제출 완료' }).first(),
    ).toBeVisible({ timeout: 10000 });
  });

  test('REVIEWER_최종_승인', async ({ reviewerPage }) => {
    const review = new ReviewPage(reviewerPage);

    // given: 상세 진입 시 PENDING → IN_REVIEW 자동 전이
    const startResponse = reviewerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${fixture.videoId}/start`) &&
        res.request().method() === 'POST',
    );
    await review.gotoDetail(fixture.videoId);
    await startResponse;

    await expect(reviewerPage.getByTestId('review-page')).toBeVisible({ timeout: 10000 });

    // when: 승인 → 확인 다이얼로그 확정
    const approveBtn = reviewerPage.getByTestId('review-action-approve');
    await expect(approveBtn).toBeVisible({ timeout: 5000 });

    const approveResponse = reviewerPage.waitForResponse(
      (res) =>
        res.url().includes(`/reviews/${fixture.videoId}/approve`) &&
        res.request().method() === 'POST',
    );
    await approveBtn.click();
    await reviewerPage.getByRole('button', { name: '승인 확정' }).click();

    const res = await approveResponse;

    // then
    expect(res.status()).toBe(200);
    await expect(
      reviewerPage.getByRole('alert').filter({ hasText: '승인 완료' }).first(),
    ).toBeVisible({ timeout: 10000 });
  });

  test('라벨링_저장_검수제출_반려_롤백_재제출_승인_전구간이_완주된다', async () => {
    // then: 전 구간을 통과했다면 대상 영상은 검수 승인(APPROVED) 상태로 종결되어 있어야 한다.
    expect(await readStatus()).toBe('APPROVED');
  });
});
