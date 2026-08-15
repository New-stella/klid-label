import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPage } from '@/pages/AugmentResultPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * DEV_FIX 2차 (FIX-A) — **화면이 하는 말과 실제 동작이 같아야 한다**.
 *
 * 구 결함: PROCESSING 배너가 "잠시 후 자동으로 결과가 표시됩니다" 라고 말하는데 결과 쿼리에는
 * 폴링도 무효화 경로도 없어, 벤더 콜백이 도착해도 화면은 영원히 "처리 중" 이었다(F5 만이 수단).
 * 게다가 같은 화면의 진행률 패널은 폴링하므로 "완료 100%" 를 그리는 동안 위 배너는 "처리 중" 인
 * **자기모순**이 관측됐다.
 *
 * 수정 방향: 결과 쿼리에 새 폴링 주기를 만들지 않고(서버에 속도 제한 없음 — 임의 주기 금지),
 * 이미 서버 힌트(`nextPollAfterMs`)를 따르는 진행률 폴링이 **종결을 관측한 시점에 결과를 무효화**
 * 한다. 자동 신호가 오지 않는 환경(외부 미연동 등)을 위해 배너에 수동 새로고침을 함께 둔다.
 */
describe('AugmentResultPage 결과 자동 갱신', () => {
  let mock: MockAdapter;
  const AUG_ID = 7;

  const item = () => ({
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
  });

  const resultBody = (status: string) => ({
    success: true,
    data: {
      jobId: 101,
      status,
      results: [item()],
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

  const mockResult = (status: string) => {
    mock.onGet('/augments/101/result').reply(200, resultBody(status));
  };

  const mockProgress = (over: Record<string, unknown>) => {
    mock.onGet(`/augments/${AUG_ID}/progress`).reply(200, {
      success: true,
      data: {
        id: AUG_ID,
        augTypeCd: 'WINTER',
        totalJobCount: 2,
        terminalJobCount: 0,
        cancelable: false,
        unavailableReason: null,
        ...over,
      },
      message: null,
      errorCode: null,
    });
  };

  const resultCallCount = () =>
    mock.history.get.filter((r) => r.url === '/augments/101/result').length;

  const renderPage = () =>
    renderWithProviders(
      <Routes>
        <Route path="/augment/result/:rawSn" element={<AugmentResultPage />} />
      </Routes>,
      { initialEntries: ['/augment/result/101'] },
    );

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('진행률이_종결되면_사용자_조작_없이_처리중_배너가_사라진다', async () => {
    // given — 처리 중. 진행률은 서버 권고 주기로 폴링한다(테스트 가속을 위해 50ms 권고).
    mockResult('PROCESSING');
    mockProgress({ status: 'RUNNING', progress: 40, nextPollAfterMs: 50 });

    renderPage();
    await screen.findByTestId('augment-result-processing');
    expect(await screen.findByTestId('augment-progress-value')).toHaveTextContent('40%');

    // when — 외부 처리가 끝났다(벤더 콜백 도착). 화면은 아무 조작도 하지 않는다.
    mock.resetHandlers();
    mockResult('COMPLETED');
    mockProgress({
      status: 'SUCCEEDED',
      progress: 100,
      terminalJobCount: 2,
      nextPollAfterMs: 0,
    });

    // then — F5 없이 배너가 사라진다
    await waitFor(
      () => {
        expect(screen.queryByTestId('augment-result-processing')).not.toBeInTheDocument();
      },
      { timeout: 3000 },
    );
    // 자기모순 제거: 진행 상태가 완료인데 "처리 중" 문구가 남아 있지 않다
    expect(screen.getByTestId('augment-progress-status')).toHaveAttribute(
      'data-status',
      'SUCCEEDED',
    );
    expect(document.body.textContent).not.toContain('증강 처리 중입니다');
  });

  it('진입_시점에_이미_종결이면_결과를_다시_받아_처리중으로_남지_않는다', async () => {
    // given — 결과 페이로드는 진입 직전 값이라 PROCESSING 인데 진행률은 이미 종결이다.
    //         (전이만 감지하면 이 자기모순은 영영 해소되지 않는다.)
    let call = 0;
    mock.onGet('/augments/101/result').reply(() => {
      call += 1;
      return [200, resultBody(call === 1 ? 'PROCESSING' : 'COMPLETED')];
    });
    mockProgress({ status: 'SUCCEEDED', progress: 100, nextPollAfterMs: 0 });

    // when
    renderPage();

    // then — 결과가 재조회되어 완료로 정정된다
    await waitFor(() => {
      expect(screen.getByTestId('augment-result-status-badge')).toHaveTextContent('완료');
    });
    expect(screen.queryByTestId('augment-result-processing')).not.toBeInTheDocument();
    expect(call).toBeGreaterThanOrEqual(2);
  });

  it('종결_상태에서_결과와_진행률이_서로를_무한히_재요청하지_않는다', async () => {
    // given — 결과 갱신이 진행률을 깨우고 그게 다시 결과를 무효화하면 순환이 된다(서버 속도 제한 없음).
    mockResult('COMPLETED');
    mockProgress({ status: 'SUCCEEDED', progress: 100, nextPollAfterMs: 0 });

    // when
    renderPage();
    await screen.findByTestId('augment-result-video-101');
    // 순환이라면 이 대기 동안 요청이 계속 쌓인다.
    await new Promise((resolve) => setTimeout(resolve, 300));

    // then — 결과는 초기 1회 + 종결 관측 1회로 끝나고, 진행률은 종결이라 더 폴링하지 않는다
    expect(resultCallCount()).toBeLessThanOrEqual(2);
    expect(
      mock.history.get.filter((r) => (r.url ?? '').includes('/progress')).length,
    ).toBeLessThanOrEqual(2);
  });

  it('자동_갱신_신호가_없는_환경에서는_새로고침으로_직접_확인할_수_있다', async () => {
    // given — 외부 미연동(NOOP)은 진행률이 종결로 가지 않아 자동 갱신 신호가 오지 않는다.
    mockResult('PROCESSING');
    mockProgress({
      status: 'RECEIVED',
      progress: null,
      unavailableReason: 'NOOP',
      nextPollAfterMs: 30_000,
    });

    renderPage();
    const banner = await screen.findByTestId('augment-result-processing');
    // 문구가 자동 표시를 단언하지 않고 수동 수단을 안내한다
    expect(banner).toHaveTextContent('갱신되지 않으면');
    const before = resultCallCount();

    // when — 실패 배너에만 있던 복구 수단을 처리 중 배너에서도 쓸 수 있다
    const user = userEvent.setup();
    await user.click(screen.getByTestId('augment-result-refresh'));

    // then — 결과를 실제로 다시 조회한다
    await waitFor(() => {
      expect(resultCallCount()).toBeGreaterThan(before);
    });
  });
});
