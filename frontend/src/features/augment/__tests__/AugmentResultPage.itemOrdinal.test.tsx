import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPage } from '@/pages/AugmentResultPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * DEV_FIX 2차 (FIX-B) — **탭 번호가 항목 페이지마다 1로 되돌아가면 안 된다**.
 *
 * 구 결함: 번호를 현재 페이지 항목 안에서만 셌기 때문에 항목 2페이지의 첫 탭도 `#1` 이었다.
 * 같은 섹션의 안내가 "왼쪽일수록 최근 요청" 이라고 말하므로, 사용자는 `#1` 을 잡 전체의 최신으로
 * 읽지만 실제로는 21번째로 최신이다(같은 종류 중복 요청이 허용돼 21건 이상이 정상 형상).
 *
 * 오프셋의 출처는 **응답이 돌려준 `itemPage`/`itemSize`** 이며 화면이 추정하지 않는다.
 */
describe('AugmentResultPage 항목 탭 번호', () => {
  let mock: MockAdapter;

  const ITEM_SIZE = 20;
  const TOTAL_ITEMS = 22;

  const winterItem = (id: number) => ({
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

  // 최신순(DESC): 0페이지 = 121..102, 1페이지 = 101, 100
  const itemsOfPage = (itemPage: number) =>
    itemPage === 0
      ? Array.from({ length: ITEM_SIZE }, (_, i) => winterItem(121 - i))
      : [winterItem(101), winterItem(100)];

  const renderPage = () =>
    renderWithProviders(
      <Routes>
        <Route path="/augment/result/:rawSn" element={<AugmentResultPage />} />
      </Routes>,
      { initialEntries: ['/augment/result/101'] },
    );

  const tabTexts = () => screen.getAllByRole('tab').map((t) => t.textContent ?? '');

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
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
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  /** 항목 축을 실제로 페이징하는 서버(현행 계약) — 응답이 itemPage/itemSize 를 돌려준다. */
  const mockPagedResult = () => {
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
  };

  it('2페이지_첫_탭이_1번으로_되돌아가지_않는다', async () => {
    // given — 같은 종류 22건(중복 요청 허용 정책상 정상 형상)
    mockPagedResult();
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => {
      expect(screen.getAllByRole('tab')).toHaveLength(ITEM_SIZE);
    });
    // 1페이지는 1번부터 시작한다(기본 회귀)
    expect(tabTexts()[0]).toContain('#1 ');
    expect(tabTexts()[ITEM_SIZE - 1]).toContain('#20 ');

    // when — 항목 2페이지로 이동
    await user.click(
      within(screen.getByTestId('augment-item-pager')).getByLabelText('다음 페이지'),
    );

    // then — 21·22번째 항목임이 드러난다(구 구현은 #1·#2 로 최신처럼 보였다)
    await waitFor(() => {
      expect(screen.getAllByRole('tab')).toHaveLength(2);
    });
    expect(tabTexts()[0]).toContain('#21 ');
    expect(tabTexts()[1]).toContain('#22 ');
    expect(tabTexts().some((t) => t.includes('#1 '))).toBe(false);
  });

  it('안내_문구가_번호의_의미를_전체_순번으로_밝힌다', async () => {
    // given
    mockPagedResult();

    // when
    renderPage();

    // then — "왼쪽일수록 최근" 과 번호의 축이 같은 축임을 사용자가 알 수 있어야 한다
    const note = await screen.findByTestId('augment-item-tab-note-101');
    expect(note).toHaveTextContent('왼쪽일수록 최근 요청');
    expect(note).toHaveTextContent('전체 항목 순번');
  });

  it('항목_페이지_정보가_없는_응답에서는_전체_순번이라고_말하지_않는다', async () => {
    // given — 항목 축 페이징을 하지 않는 구 응답(itemPage/itemSize 없음). 오프셋을 추정하지 않는다.
    mock.onGet('/augments/101/result').reply(200, {
      success: true,
      data: {
        jobId: 101,
        status: 'COMPLETED',
        results: [winterItem(121), winterItem(120)],
        message: null,
      },
      message: null,
      errorCode: null,
    });

    // when
    renderPage();

    // then — 번호는 그대로 매기되 그 근거를 화면 순서라고 정직하게 말한다
    const note = await screen.findByTestId('augment-item-tab-note-101');
    expect(note).toHaveTextContent('화면에 보이는 순서');
    expect(note.textContent ?? '').not.toContain('전체 항목 순번');
    expect(tabTexts()[0]).toContain('#1 ');
  });
});
