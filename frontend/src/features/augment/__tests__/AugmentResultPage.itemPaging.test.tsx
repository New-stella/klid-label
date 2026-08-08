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
 * Phase 5 (E) — **항목 축 페이저**.
 *
 * 결과 조회는 축이 둘이다: 프레임 쌍(page/size, 기본 12) · 결과 항목(itemPage/itemSize, 기본 20).
 * FE 가 항목 축을 소비하지 않으면 21번째 항목부터 화면에서 사라진다. 특히 순수 외부 위탁 잡은
 * 프레임 쌍이 0건이라 기존 프레임 페이저(총량 > 12 조건)가 아예 렌더되지 않아 2페이지로 갈
 * 컨트롤 자체가 없었다 — 해상도 파생 1건이 뒤로 밀리면 **비교 이미지에 도달 불가**.
 *
 * 수용 기준: 외부 위탁 21건 + 해상도 파생 1건인 영상에서 비교 이미지가 보여야 한다.
 */
describe('AugmentResultPage 항목 축 페이징', () => {
  let mock: MockAdapter;

  const TOTAL_EXTERNAL = 21;
  const ITEM_SIZE = 20;
  const TOTAL_ITEMS = TOTAL_EXTERNAL + 1; // + 해상도 파생 1건

  const externalItem = (id: number) => ({
    id,
    videoId: 101,
    cctvName: 'CCTV-01',
    type: 'WINTER',
    framePairs: [],
    decision: 'ACCEPTED',
    decidedAt: '2026-07-30T10:00:00',
    rejectReason: null,
    derivativeRawSn: null,
    totalFramePairs: 0,
    reviewable: false,
    prompt: null,
  });

  const resolutionItem = () => ({
    id: 900,
    videoId: 101,
    cctvName: 'CCTV-01',
    type: 'RESL_720P',
    framePairs: [
      {
        srcSn: 9000,
        frameNo: 0,
        originalUrl: '/v1/frames/8000/deid-image',
        augmentedUrl: '/v1/frames/9000/deid-image',
      },
    ],
    decision: 'ACCEPTED',
    decidedAt: '2026-07-30T10:00:00',
    rejectReason: null,
    derivativeRawSn: 102,
    totalFramePairs: 1,
    reviewable: false,
    prompt: null,
  });

  // 항목 축: 0페이지 = 외부 20건 / 1페이지 = 외부 1건 + 해상도 1건
  const itemsOfPage = (itemPage: number) =>
    itemPage === 0
      ? Array.from({ length: ITEM_SIZE }, (_, i) => externalItem(100 + i))
      : [externalItem(100 + ITEM_SIZE), resolutionItem()];

  const renderPage = () =>
    renderWithProviders(
      <Routes>
        <Route path="/augment/result/:jobId" element={<AugmentResultPage />} />
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
    mock.onGet(/\/augments\/\d+\/progress$/).reply(200, {
      success: true,
      data: {
        id: 100,
        augTypeCd: 'WINTER',
        status: 'SUCCEEDED',
        progress: 100,
        unavailableReason: null,
        totalJobCount: 1,
        terminalJobCount: 1,
        cancelable: false,
        nextPollAfterMs: 0,
      },
      message: null,
      errorCode: null,
    });
    mock.onGet('/augments/101/result').reply((config) => {
      const itemPage = Number(config.params?.itemPage ?? 0);
      return [
        200,
        {
          success: true,
          data: {
            jobId: 101,
            status: 'COMPLETED',
            results: itemsOfPage(itemPage),
            message: null,
            page: Number(config.params?.page ?? 0),
            size: Number(config.params?.size ?? 12),
            itemPage,
            itemSize: ITEM_SIZE,
            totalElements: TOTAL_ITEMS,
            totalPages: 2,
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

  it('항목_페이저는_프레임쌍이_0건이어도_렌더된다', async () => {
    // given / when
    renderPage();

    // then — 1페이지 항목은 전부 프레임 쌍 0건인데도 항목 페이저가 보인다
    await waitFor(() => {
      expect(screen.getByTestId('augment-item-pager')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('augment-frame-pager')).not.toBeInTheDocument();
    // 항목 축 총량 기준으로 렌더된다 — 응답의 항목 축 totalPages(2) 만큼 번호가 나온다.
    // 프레임 쌍 축(이 페이지는 0건)을 따랐다면 1페이지뿐이라 2페이지로 갈 길이 없다.
    // ⚠ 구 단언은 페이저 안의 "총 22건" 표기를 봤으나, 그 건수 표기는 사양(UI-008)에서
    //   컨트롤 밖으로 나갔다 — 같은 뜻을 페이지 번호 축으로 옮겨 단언한다.
    const pager = within(screen.getByTestId('augment-item-pager'));
    expect(pager.getByRole('button', { name: '2페이지' })).toBeInTheDocument();
    expect(pager.queryByRole('button', { name: '3페이지' })).not.toBeInTheDocument();
    // 최초 조회부터 항목 축 파라미터를 보낸다
    const first = mock.history.get.find((r) => r.url === '/augments/101/result');
    expect(first?.params).toMatchObject({ itemPage: 0, itemSize: ITEM_SIZE });
  });

  it('외부위탁_21건_해상도1건_영상에서_비교이미지가_보인다', async () => {
    // given — 1페이지에는 비교 이미지가 없다
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId('augment-item-pager')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('frame-pair-9000-original')).not.toBeInTheDocument();

    // when — 항목 페이저로 2페이지 이동 후 해상도 탭 선택
    await user.click(
      within(screen.getByTestId('augment-item-pager')).getByLabelText('다음 페이지'),
    );
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /해상도 720p/i })).toBeInTheDocument();
    });
    await user.click(screen.getByRole('tab', { name: /해상도 720p/i }));

    // then — 비교 이미지가 보인다
    await waitFor(() => {
      expect(screen.getByTestId('frame-pair-9000-original')).toBeInTheDocument();
    });
    expect(screen.getByTestId('frame-pair-9000-processed')).toBeInTheDocument();
    // BE 로 itemPage=1 이 나갔다
    const last = mock.history.get.filter((r) => r.url === '/augments/101/result').at(-1);
    expect(last?.params).toMatchObject({ itemPage: 1 });
  });

  it('항목_페이지를_넘기면_프레임_페이지는_첫_페이지로_돌아간다', async () => {
    // given
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId('augment-item-pager')).toBeInTheDocument();
    });

    // when
    await user.click(
      within(screen.getByTestId('augment-item-pager')).getByLabelText('다음 페이지'),
    );

    // then — 항목이 바뀌면 프레임 축 페이지를 유지할 근거가 없다(빈 그리드 갇힘 방지)
    await waitFor(() => {
      const last = mock.history.get.filter((r) => r.url === '/augments/101/result').at(-1);
      expect(last?.params).toMatchObject({ itemPage: 1, page: 0 });
    });
  });
});
