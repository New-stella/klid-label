import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPanel } from '@/features/augment/components/AugmentResultPanel';
import type { AugmentResult } from '@/features/augment/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * DEV_FIX 회귀 — **빈 프레임 페이지에 갇히지 않는다**.
 *
 * 결함: 페이저 표시 조건(`totalPairs > 12 || framePage > 0`)이 `gridFrames.length > 0` 분기 <안>에
 * 있어, 정작 그리드가 빈 순간에는 안전망이 없었다. 대신 "프레임별 비교 결과는 **외부 연동 이후**
 * 표시됩니다" 가 떴는데, 쌍이 실재하는 해상도 파생 항목에 대해서는 **사실과 다른 안내**다.
 *
 * 재현 형상: 총량이 서로 다른 탭 사이를 옮기는 순간(1080P 쌍 30 · 720P 쌍 5) BE 가 이미 stale 한
 * `page=2` 로 720P 의 빈 슬라이스를 내려준 상태 — 즉 `framePairs=[]` 인데 `totalFramePairs>0`.
 */
describe('AugmentResultPanel 빈 프레임 페이지', () => {
  let mock: MockAdapter;

  const result: AugmentResult = {
    id: 55,
    videoId: 101,
    cctvName: 'CCTV-01',
    type: 'RESL_720P',
    framePairs: [], // stale 페이지의 빈 슬라이스
    totalFramePairs: 5, // 쌍은 실재한다
    decision: 'ACCEPTED',
    decidedAt: '2026-07-28T10:00:00',
    reviewable: false,
    derivativeRawSn: 102,
    prompt: null,
  };

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet(/\/augments\/\d+\/progress$/).reply(400, {
      success: false,
      data: null,
      message: 'not applicable',
      errorCode: 'INVALID_INPUT',
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

  it('쌍이_실재하는데_페이지가_비면_되돌아갈_컨트롤을_준다', async () => {
    // given
    const onFramePageChange = vi.fn();
    const user = userEvent.setup();

    // when — 총량(5)보다 뒤쪽 페이지를 보고 있는 상태
    renderWithProviders(
      <AugmentResultPanel
        result={result}
        framePage={2}
        onFramePageChange={onFramePageChange}
      />,
    );

    // then — 오안내("외부 연동 이후")가 아니라 빈 페이지 사실 + 복귀 컨트롤
    const notice = await screen.findByTestId('augment-result-empty-page-55');
    expect(notice).toHaveTextContent('이 페이지에는 표시할 프레임 쌍이 없습니다');
    expect(notice).toHaveTextContent('전체 5쌍');
    expect(
      screen.queryByTestId('augment-result-no-pairs-55'),
    ).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('외부 연동 이후');

    // when — 첫 페이지로 복귀
    await user.click(screen.getByTestId('augment-frame-first-page-55'));

    // then
    expect(onFramePageChange).toHaveBeenCalledWith(0);
  });

  it('쌍이_실재하고_총량이_한_페이지를_넘으면_페이저도_함께_준다', async () => {
    // given — 총 30쌍(3페이지)인데 지금 페이지가 비어 있는 상태
    const onFramePageChange = vi.fn();

    // when
    renderWithProviders(
      <AugmentResultPanel
        result={{ ...result, totalFramePairs: 30 }}
        framePage={4}
        onFramePageChange={onFramePageChange}
      />,
    );

    // then — 임의 페이지로도 이동할 수 있어야 접근 불가 프레임이 0 이 된다
    expect(
      await screen.findByTestId('augment-result-empty-page-55'),
    ).toBeInTheDocument();
    expect(screen.getByTestId('augment-frame-pager')).toBeInTheDocument();
  });

  it('쌍이_아예_없는_외부위탁_항목에는_기존_안내를_유지한다', async () => {
    // given — 외부 위탁 항목은 프레임별 산출물 연동 전이라 쌍이 실제로 0건이다
    const onFramePageChange = vi.fn();

    // when
    renderWithProviders(
      <AugmentResultPanel
        result={{ ...result, id: 56, type: 'WINTER', totalFramePairs: 0 }}
        framePage={0}
        onFramePageChange={onFramePageChange}
      />,
    );

    // then
    expect(
      await screen.findByTestId('augment-result-no-pairs-56'),
    ).toHaveTextContent('외부 연동 이후');
    expect(
      screen.queryByTestId('augment-result-empty-page-56'),
    ).not.toBeInTheDocument();
  });
});
