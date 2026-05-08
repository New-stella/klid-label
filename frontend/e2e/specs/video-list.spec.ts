import { test, expect } from '../fixtures/auth.fixture';
import { VideoListPage } from '../pages/VideoListPage';

test.describe('영상 목록 — 검색/필터/페이지/URL 동기화', () => {
  test('검색어_입력_시_URL_쿼리_파라미터_업데이트', async ({ workerPage }) => {
    const list = new VideoListPage(workerPage);
    await list.goto();

    await list.search('테스트');

    // mock 정합 — 검색 파라미터는 cctvNameKeyword (CCTV 명 부분 일치).
    await expect(workerPage).toHaveURL(/cctvNameKeyword=/);
  });

  test('페이지_이동_시_URL_page_파라미터_갱신', async ({ workerPage }) => {
    const list = new VideoListPage(workerPage);
    await list.goto();

    // 페이지네이션 다음 버튼이 존재하면 클릭, 없으면 스킵 (데이터 의존)
    const next = workerPage.getByRole('button', { name: /다음|next/i });
    if ((await next.count()) > 0 && (await next.isEnabled())) {
      await next.click();
      await expect(workerPage).toHaveURL(/page=/);
    }
  });
});
