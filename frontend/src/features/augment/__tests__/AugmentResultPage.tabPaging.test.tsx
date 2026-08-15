import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPage } from '@/pages/AugmentResultPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 유형 탭 전환 × 프레임 페이징 회귀.
 *
 * 결함: 프레임 페이지(`framePage`)가 화면 전역 1개인데 탭 전환 시 리셋되지 않아,
 * 총량이 큰 탭에서 2페이지로 간 뒤 총량이 작은 탭으로 옮기면
 *  - BE 가 빈 슬라이스를 내려 그리드가 0장이 되고
 *  - 페이저 표시 조건(`총량 > 12`)이 거짓이라 되돌아갈 컨트롤조차 사라진다(갇힘).
 *
 * 기존 테스트가 못 잡은 이유: `results` 를 1종(RESL_720P)만 목업했다.
 * 여기서는 **총량이 서로 다른 2종**(1080P=15, 480P=10)을 목업한다.
 */
describe('AugmentResultPage 유형 탭 전환 × 프레임 페이징', () => {
  let mock: MockAdapter;

  const TOTAL_1080P = 15;
  const TOTAL_480P = 10;

  /**
   * ⚠ 이 목업은 **요청 파라미터 `page` 를 반드시 반영**해야 한다(아래 `resultOf` → `pairs`).
   * 페이지를 무시하고 항상 같은 쌍을 돌려주면 "총량이 작은 탭에서 stale 페이지 → 빈 그리드" 라는
   * 이 파일이 지키려는 결함 축이 통째로 관측 불가가 된다. 빈 그리드 상태의 복구 컨트롤 자체는
   * `AugmentResultPanel.framePage.test.tsx` 가 결정론적으로 검증한다(전이 타이밍 비의존).
   */
  const pairs = (total: number, srcSnBase: number, page: number, size: number) => {
    const from = page * size;
    const to = Math.min(total, from + size);
    return Array.from({ length: Math.max(0, to - from) }, (_, i) => {
      const frameNo = from + i;
      return {
        srcSn: srcSnBase + frameNo,
        frameNo,
        originalUrl: `/v1/frames/${5000 + frameNo}/deid-image`,
        augmentedUrl: `/v1/frames/${srcSnBase + frameNo}/deid-image`,
      };
    });
  };

  const resultOf = (
    id: number,
    type: string,
    total: number,
    srcSnBase: number,
    page: number,
    size: number,
  ) => ({
    id,
    videoId: 101,
    cctvName: 'CCTV-01',
    type,
    decision: 'ACCEPTED',
    decidedAt: '2026-07-28T10:00:00',
    rejectReason: null,
    derivativeRawSn: srcSnBase,
    totalFramePairs: total,
    reviewable: false,
    framePairs: pairs(total, srcSnBase, page, size),
  });

  const renderPage = () =>
    renderWithProviders(
      <Routes>
        <Route path="/augment/result/:rawSn" element={<AugmentResultPage />} />
      </Routes>,
      { initialEntries: ['/augment/result/101'] },
    );

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    let seq = 0;
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn(() => `blob:mock/${++seq}`),
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn(),
    });
    mock.onGet(/\/frames\/\d+\/deid-image$/).reply(200, new Blob(['img']));
    mock.onGet('/augments/101/result').reply((config) => {
      const page = Number(config.params?.page ?? 0);
      const size = Number(config.params?.size ?? 12);
      return [
        200,
        {
          success: true,
          data: {
            jobId: 101,
            status: 'COMPLETED',
            page,
            size,
            results: [
              resultOf(11, 'RESL_1080P', TOTAL_1080P, 9000, page, size),
              resultOf(12, 'RESL_480P', TOTAL_480P, 7000, page, size),
            ],
            message: null,
          },
          message: null,
          errorCode: null,
        },
      ];
    });
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  const gridCardCount = () =>
    within(screen.getByTestId('frame-grid-12')).queryAllByRole('radio').length;

  it('총량이_작은_탭으로_전환하면_프레임페이지가_리셋되어_그리드가_비지_않는다', async () => {
    // given — 1080P(15장) 탭에서 2페이지로 이동
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId('frame-pair-9000-original')).toBeInTheDocument();
    });
    await user.click(
      within(screen.getByTestId('augment-frame-pager')).getByLabelText('다음 페이지'),
    );
    await waitFor(() => {
      expect(screen.getByTestId('frame-pair-9012-original')).toBeInTheDocument();
    });

    // when — 총량이 더 작은 480P(10장) 탭으로 전환 (page=1 이면 빈 슬라이스가 된다)
    await user.click(screen.getByRole('tab', { name: /해상도 480p/i }));

    // then — 첫 페이지로 리셋되어 10장이 모두 보인다 (빈 그리드에 갇히지 않는다)
    await waitFor(() => {
      expect(screen.getByTestId('frame-pair-7000-original')).toBeInTheDocument();
    });
    expect(gridCardCount()).toBe(TOTAL_480P);
    // 총량 10 ≤ 12 이므로 페이저는 필요 없다 — 대신 그리드가 채워져 있어야 한다
    expect(screen.queryByTestId('augment-frame-pager')).not.toBeInTheDocument();
    // BE 로 page=0 재조회가 나갔다
    const last = mock.history.get.filter((r) => r.url === '/augments/101/result').at(-1);
    expect(last?.params).toMatchObject({ page: 0, size: 12 });
  });

  it('탭을_되돌려도_첫_페이지부터_정상_표시된다', async () => {
    // given
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId('frame-pair-9000-original')).toBeInTheDocument();
    });
    await user.click(
      within(screen.getByTestId('augment-frame-pager')).getByLabelText('다음 페이지'),
    );
    await waitFor(() => {
      expect(screen.getByTestId('frame-pair-9012-original')).toBeInTheDocument();
    });
    await user.click(screen.getByRole('tab', { name: /해상도 480p/i }));
    await waitFor(() => {
      expect(screen.getByTestId('frame-pair-7000-original')).toBeInTheDocument();
    });

    // when — 다시 1080P 탭
    await user.click(screen.getByRole('tab', { name: /해상도 1080p/i }));

    // then — 1페이지(12장) + 페이저가 함께 보인다
    await waitFor(() => {
      expect(screen.getByTestId('frame-pair-9000-original')).toBeInTheDocument();
    });
    expect(gridCardCount()).toBe(12);
    expect(screen.getByTestId('augment-frame-pager')).toBeInTheDocument();
  });
});
