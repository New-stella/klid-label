import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { AugmentProgressPanel } from '@/features/augment/components/AugmentProgressPanel';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

/**
 * DEV_FIX 회귀 — 진행 상태 패널의 "사실과 다른 표시" 4건.
 *
 * ① 조회 실패 시 자동 폴링이 멈춘 사실을 알리고 되살릴 수단을 준다(무한 폴링 제거의 짝).
 * ② 취소 확인 모달 건수는 **비종결 청크 수**다(`total - terminal`) — 취소 응답과 모순되면 안 된다.
 * ③ 모르는 `unavailableReason` 이 와도 안내가 통째로 사라지지 않는다(fail-open 금지).
 * ④ 부분 취소는 성공 토스트로 알리지 않는다(바로 아래 warning 카드와 신호가 어긋난다).
 */
describe('AugmentProgressPanel 표시 정합', () => {
  let mock: MockAdapter;
  const AUG_ID = 7;

  const progressReply = (over: Record<string, unknown> = {}) => ({
    success: true,
    data: {
      id: AUG_ID,
      augTypeCd: 'WINTER',
      status: 'RUNNING',
      progress: 40,
      unavailableReason: null,
      totalJobCount: 10,
      terminalJobCount: 8,
      cancelable: true,
      nextPollAfterMs: 3000,
      ...over,
    },
    message: null,
    errorCode: null,
  });

  const renderPanel = () =>
    renderWithProviders(<AugmentProgressPanel augmentId={AUG_ID} enabled />);

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useUiStore.setState({ toasts: [] });
  });

  it('조회_실패시_안내와_수동_재시도_수단을_준다', async () => {
    // given — 진행률 API 500 (자동 폴링은 중단된다)
    mock.onGet(`/augments/${AUG_ID}/progress`).reply(500, {
      success: false,
      data: null,
      message: 'boom',
      errorCode: 'INTERNAL_ERROR',
    });
    const user = userEvent.setup();

    // when
    renderPanel();
    const retry = await screen.findByTestId(`augment-progress-retry-${AUG_ID}`);
    const before = mock.history.get.filter((r) =>
      (r.url ?? '').includes('/progress'),
    ).length;

    // then — 안내 + 재시도 버튼. 버튼을 누르면 정확히 1회 재조회한다(등간격 폭주 아님).
    expect(screen.getByTestId(`augment-progress-error-${AUG_ID}`)).toHaveTextContent(
      '진행 상태를 불러오지 못했습니다',
    );
    await user.click(retry);
    await waitFor(() => {
      const after = mock.history.get.filter((r) =>
        (r.url ?? '').includes('/progress'),
      ).length;
      expect(after).toBe(before + 1);
    });
  });

  it('취소_확인모달은_비종결_청크_수를_말한다', async () => {
    // given — 위탁 청크 10개 중 8개는 이미 종결 → 취소 대상은 2건이다
    mock.onGet(`/augments/${AUG_ID}/progress`).reply(200, progressReply());
    const user = userEvent.setup();
    renderPanel();

    // when
    await user.click(await screen.findByTestId(`augment-cancel-${AUG_ID}`));

    // then — 총 수(10건)를 "진행 중" 이라고 말하면 취소 응답(2건 중)과 자기모순이 된다
    const notice = await screen.findByTestId('augment-cancel-target-count');
    expect(notice).toHaveTextContent('진행 중인 작업 2건');
    expect(notice.textContent ?? '').not.toContain('10건');
  });

  it('모르는_진행률_불가사유가_와도_안내가_사라지지_않는다', async () => {
    // given — BE 가 5번째 사유를 추가한 상황(구 코드는 안내·진행률이 통째로 사라졌다)
    mock.onGet(`/augments/${AUG_ID}/progress`).reply(
      200,
      progressReply({ progress: null, unavailableReason: 'VENDOR_MAINTENANCE' }),
    );

    // when
    renderPanel();

    // then
    const notice = await screen.findByTestId('augment-progress-unavailable');
    expect(notice).toHaveAttribute('data-reason', 'VENDOR_MAINTENANCE');
    expect(notice).toHaveTextContent('진행률을 표시할 수 없습니다');
    expect(notice).not.toHaveAttribute('role', 'alert');
  });

  it('부분_취소는_성공_토스트로_알리지_않는다', async () => {
    // given — 3청크 중 1건만 외부 취소 성립
    mock.onGet(`/augments/${AUG_ID}/progress`).reply(200, progressReply());
    mock.onPost(`/augments/${AUG_ID}/cancel`).reply(200, {
      success: true,
      data: {
        id: AUG_ID,
        augTypeCd: 'WINTER',
        status: 'CANCELED',
        canceled: true,
        fullyCanceled: false,
        targetJobCount: 3,
        canceledJobCount: 1,
        failedJobSeqs: [2, 3],
        message: '일부 작업은 외부 시스템에 취소가 전달되지 않았습니다.',
      },
      message: null,
      errorCode: null,
    });
    const user = userEvent.setup();
    renderPanel();

    // when
    await user.click(await screen.findByTestId(`augment-cancel-${AUG_ID}`));
    await user.click(await screen.findByRole('button', { name: '취소 확정' }));

    // then — 카드는 warning 인데 토스트만 초록이면 신호가 어긋난다
    await waitFor(() => {
      expect(useUiStore.getState().toasts).toHaveLength(1);
    });
    expect(useUiStore.getState().toasts[0].variant).toBe('warning');
  });

  it('완전_취소는_성공_토스트로_알린다', async () => {
    // given
    mock.onGet(`/augments/${AUG_ID}/progress`).reply(200, progressReply());
    mock.onPost(`/augments/${AUG_ID}/cancel`).reply(200, {
      success: true,
      data: {
        id: AUG_ID,
        augTypeCd: 'WINTER',
        status: 'CANCELED',
        canceled: true,
        fullyCanceled: true,
        targetJobCount: 2,
        canceledJobCount: 2,
        failedJobSeqs: [],
        message: '증강 요청을 취소했습니다.',
      },
      message: null,
      errorCode: null,
    });
    const user = userEvent.setup();
    renderPanel();

    // when
    await user.click(await screen.findByTestId(`augment-cancel-${AUG_ID}`));
    await user.click(await screen.findByRole('button', { name: '취소 확정' }));

    // then
    await waitFor(() => {
      expect(useUiStore.getState().toasts).toHaveLength(1);
    });
    expect(useUiStore.getState().toasts[0].variant).toBe('success');
  });
});
