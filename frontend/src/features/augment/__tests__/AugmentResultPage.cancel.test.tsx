import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPage } from '@/pages/AugmentResultPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Phase 5 (C) — 증강 요청 취소(REVIEWER 전용).
 *
 * - 되돌릴 수 없는 조작이라 확인 모달을 경유한다.
 * - 응답 shape 이 accept/reject 와 다르다(`AugmentCancelResponse`) — 부분 취소를 표현하기 위함.
 * - `fullyCanceled=false` 면 그 사실을 사용자에게 알리되 **재시도를 임의로 안내하지 않는다**
 *   (증강이 이미 종결이라 재요청은 멱등 200 이다 — 수행 불가능한 동선).
 * - 연타 방어는 이 화면 책임(요청 화면 `submitLockRef` 패턴 재사용).
 */
describe('AugmentResultPage 증강 취소', () => {
  let mock: MockAdapter;
  const AUG_ID = 7;

  const mockResult = () => {
    mock.onGet('/augments/101/result').reply(200, {
      success: true,
      data: {
        jobId: 101,
        status: 'PROCESSING',
        results: [
          {
            id: AUG_ID,
            videoId: 101,
            cctvName: 'CCTV-01',
            type: 'WINTER',
            framePairs: [],
            decision: 'PENDING',
            decidedAt: null,
            rejectReason: null,
            derivativeRawSn: null,
            totalFramePairs: 0,
            reviewable: true,
            prompt: null,
          },
        ],
        message: null,
        page: 0,
        size: 12,
        itemPage: 0,
        itemSize: 20,
        totalElements: 1,
        totalPages: 1,
      },
      message: null,
      errorCode: null,
    });
  };

  const mockProgress = (cancelable = true) => {
    mock.onGet(`/augments/${AUG_ID}/progress`).reply(200, {
      success: true,
      data: {
        id: AUG_ID,
        augTypeCd: 'WINTER',
        status: 'RUNNING',
        progress: 40,
        unavailableReason: null,
        totalJobCount: 3,
        terminalJobCount: 0,
        cancelable,
        nextPollAfterMs: 3000,
      },
      message: null,
      errorCode: null,
    });
  };

  const cancelReply = (over: Record<string, unknown> = {}) => ({
    success: true,
    data: {
      id: AUG_ID,
      augTypeCd: 'WINTER',
      status: 'CANCELED',
      canceled: true,
      fullyCanceled: true,
      targetJobCount: 3,
      canceledJobCount: 3,
      failedJobSeqs: [],
      message: '증강 요청을 취소했습니다.',
      ...over,
    },
    message: null,
    errorCode: null,
  });

  const renderPage = () =>
    renderWithProviders(
      <Routes>
        <Route path="/augment/result/:rawSn" element={<AugmentResultPage />} />
      </Routes>,
      { initialEntries: ['/augment/result/101'] },
    );

  const setRole = (role: 'REVIEWER' | 'WORKER') =>
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role, channel: 'INTERNAL', exp: 9999999999 },
    });

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mockResult();
    mockProgress();
    setRole('REVIEWER');
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('WORKER_에게는_취소버튼이_보이지_않는다', async () => {
    // given
    setRole('WORKER');

    // when
    renderPage();

    // then
    await waitFor(() => {
      expect(screen.getByTestId(`augment-progress-${AUG_ID}`)).toBeInTheDocument();
    });
    expect(
      screen.queryByTestId(`augment-cancel-${AUG_ID}`),
    ).not.toBeInTheDocument();
  });

  it('취소_확인모달에서_승인해야_취소가_호출된다', async () => {
    // given
    const user = userEvent.setup();
    mock.onPost(`/augments/${AUG_ID}/cancel`).reply(200, cancelReply());
    renderPage();
    const cancelBtn = await screen.findByTestId(`augment-cancel-${AUG_ID}`);

    // when — 버튼만 눌렀을 때는 전송되지 않는다
    await user.click(cancelBtn);
    expect(mock.history.post).toHaveLength(0);
    expect(await screen.findByTestId('augment-cancel-modal')).toBeInTheDocument();

    // when — 모달에서 확정
    await user.click(screen.getByRole('button', { name: '취소 확정' }));

    // then
    await waitFor(() => {
      expect(mock.history.post).toHaveLength(1);
    });
    expect(mock.history.post[0].url).toBe(`/augments/${AUG_ID}/cancel`);
    // 바디는 reason 만 보낸다(Mass Assignment 방어 — 다른 필드 금지)
    const body = JSON.parse(mock.history.post[0].data ?? '{}') as Record<string, unknown>;
    expect(Object.keys(body).every((k) => k === 'reason')).toBe(true);
  });

  it('취소_연타해도_1회만_전송된다', async () => {
    // given
    const user = userEvent.setup();
    mock.onPost(`/augments/${AUG_ID}/cancel`).reply(200, cancelReply());
    renderPage();
    await user.click(await screen.findByTestId(`augment-cancel-${AUG_ID}`));
    const confirm = await screen.findByRole('button', { name: '취소 확정' });

    // when — 같은 tick 에 연속 클릭
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    fireEvent.click(confirm);

    // then
    await waitFor(() => {
      expect(mock.history.post).toHaveLength(1);
    });
  });

  it('부분취소면_그_사실이_사용자에게_보인다', async () => {
    // given — 3청크 중 1건만 외부 취소 성립
    const user = userEvent.setup();
    mock.onPost(`/augments/${AUG_ID}/cancel`).reply(
      200,
      cancelReply({
        fullyCanceled: false,
        canceledJobCount: 1,
        failedJobSeqs: [2, 3],
        message: '일부 작업은 외부 시스템에 취소가 전달되지 않았습니다.',
      }),
    );
    renderPage();
    await user.click(await screen.findByTestId(`augment-cancel-${AUG_ID}`));
    await user.click(await screen.findByRole('button', { name: '취소 확정' }));

    // then
    const result = await screen.findByTestId('augment-cancel-result');
    expect(result).toHaveTextContent('일부 작업은 외부 시스템에 취소가 전달되지 않았습니다.');
    expect(result).toHaveTextContent('3건 중 1건');
    // 수행 불가능한 동선(재시도)을 안내하지 않는다
    expect(result.textContent ?? '').not.toContain('다시 시도');
    expect(result.textContent ?? '').not.toContain('재시도');
  });

  it('취소하면_결과와_진행상태를_다시_조회한다', async () => {
    // given
    const user = userEvent.setup();
    mock.onPost(`/augments/${AUG_ID}/cancel`).reply(200, cancelReply());
    renderPage();
    await screen.findByTestId(`augment-cancel-${AUG_ID}`);
    const before = mock.history.get.filter(
      (r) => r.url === '/augments/101/result',
    ).length;

    // when
    await user.click(screen.getByTestId(`augment-cancel-${AUG_ID}`));
    await user.click(await screen.findByRole('button', { name: '취소 확정' }));

    // then — 증강 도메인 캐시 무효화로 화면이 CANCELED 로 갱신될 수 있어야 한다
    await waitFor(() => {
      const after = mock.history.get.filter(
        (r) => r.url === '/augments/101/result',
      ).length;
      expect(after).toBeGreaterThan(before);
    });
  });

  it('이미_종결된_증강의_멱등_응답은_오류로_표시하지_않는다', async () => {
    // given — canceled=false + 종결 status = 멱등 200
    const user = userEvent.setup();
    mock.onPost(`/augments/${AUG_ID}/cancel`).reply(
      200,
      cancelReply({
        canceled: false,
        fullyCanceled: true,
        targetJobCount: 0,
        canceledJobCount: 0,
        message: '이미 종결된 증강입니다.',
      }),
    );
    renderPage();
    await user.click(await screen.findByTestId(`augment-cancel-${AUG_ID}`));
    await user.click(await screen.findByRole('button', { name: '취소 확정' }));

    // then
    const result = await screen.findByTestId('augment-cancel-result');
    expect(result).toHaveTextContent('이미 종결된 증강입니다.');
    expect(result).not.toHaveAttribute('role', 'alert');
  });

  it('취소_불가_상태면_버튼을_노출하지_않는다', async () => {
    // given — cancelable=false (증강 행이 PENDING 이 아님)
    mock.resetHandlers();
    mockResult();
    mockProgress(false);

    // when
    renderPage();

    // then
    await waitFor(() => {
      expect(screen.getByTestId(`augment-progress-${AUG_ID}`)).toBeInTheDocument();
    });
    expect(screen.queryByTestId(`augment-cancel-${AUG_ID}`)).not.toBeInTheDocument();
  });
});
