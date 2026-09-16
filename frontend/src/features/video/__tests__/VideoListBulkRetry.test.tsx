// 영상 목록 — 배치 일괄 재시작. [@design SCREEN-008] [@design API-199]

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { VideoListPage } from '@/pages/VideoListPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

function videoRow(id: number) {
  return {
    id,
    cctvName: `CCTV-${id}`,
    vmsClipId: `VMS-${id}`,
    eventName: '쓰러짐',
    eventTypeCd: 'FALL',
    frameCount: 900,
    status: 'FAILED',
    capturedAt: '2026-05-01T12:00:00Z',
  };
}

/**
 * 선두 비식별이 실패한 영상 행 — 배치 상태는 실패가 아니라 **대기**로 남고, 비식별만 실패를 말한다.
 * (서버 목록 응답 실측 모양: `status=PENDING` · `deIdntfYn=F` · `deidentStatus=FAILED`)
 */
function deidentFailedRow(id: number) {
  return { ...videoRow(id), status: 'PENDING', deIdntfYn: 'F', deidentStatus: 'FAILED' };
}

function mockVideos(
  mock: MockAdapter,
  count: number,
  rowOf: (id: number) => Record<string, unknown> = videoRow,
) {
  const content = Array.from({ length: count }, (_, i) => rowOf(i + 1));
  mock.onGet('/videos').reply(200, {
    success: true,
    data: {
      content,
      totalElements: count,
      totalPages: 1,
      number: 0,
      size: Math.max(count, 20),
    },
    message: null,
    errorCode: null,
  });
}

function setReviewer() {
  useAuthStore.setState({
    token: 'dummy-test-token',
    claims: {
      sub: 'u-1',
      role: 'REVIEWER',
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

describe('VideoListPage 일괄 재시작', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setReviewer();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function selectFirstTwo() {
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    await user.click(screen.getByRole('checkbox', { name: 'CCTV-1 선택' }));
    await user.click(screen.getByRole('checkbox', { name: 'CCTV-2 선택' }));
    return user;
  }

  it('선택한_영상을_일괄_재시작_요청으로_보낸다', async () => {
    mockVideos(mock, 2);
    mock.onPost('/videos/batch/retry').reply(200, {
      success: true,
      data: {
        successCount: 2,
        failureCount: 0,
        results: [
          { rawSn: 1, success: true, reason: null },
          { rawSn: 2, success: true, reason: null },
        ],
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();

    await user.click(screen.getByRole('button', { name: '2개 영상 배치 일괄 재시작' }));

    await waitFor(() => {
      const call = mock.history.post.find((h) => h.url === '/videos/batch/retry');
      expect(call).toBeDefined();
      expect(JSON.parse(call!.data)).toEqual({ rawSns: [1, 2] });
    });
  });

  it('부분_성공이면_접수_건수와_접수하지_못한_사유를_보여준다', async () => {
    // 서버는 한 건도 접수 못 해도 200 이므로 화면이 결과 목록으로 판정해야 한다.
    mockVideos(mock, 2);
    mock.onPost('/videos/batch/retry').reply(200, {
      success: true,
      data: {
        successCount: 1,
        failureCount: 1,
        results: [
          { rawSn: 1, success: true, reason: null },
          { rawSn: 2, success: false, reason: '이미 재처리가 진행 중입니다.' },
        ],
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 배치 일괄 재시작' }));

    const result = await screen.findByTestId('bulk-retry-result');
    expect(result).toHaveTextContent('접수 1건');
    expect(result).toHaveTextContent('접수하지 못함 1건');

    // 접수되지 못한 분은 어느 영상이 왜 안 됐는지까지 보여준다.
    const failure = within(result).getByTestId('bulk-retry-failure-2');
    expect(failure).toHaveTextContent('CCTV-2');
    expect(failure).toHaveTextContent('이미 재처리가 진행 중입니다.');
  });

  it('결과는_접수를_뜻하고_완료로_읽히는_문구를_쓰지_않는다', async () => {
    // ★ 서버는 실패 상태 선점까지만 요청 안에서 처리하고 실제 실행은 비동기로 넘긴다.
    //   "재시작됐다/완료됐다"로 읽히면 사용자는 결과를 다 본 것으로 오해하고 진행을 확인하지 않는다.
    mockVideos(mock, 2);
    mock.onPost('/videos/batch/retry').reply(200, {
      success: true,
      data: {
        successCount: 2,
        failureCount: 0,
        results: [
          { rawSn: 1, success: true, reason: null },
          { rawSn: 2, success: true, reason: null },
        ],
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 배치 일괄 재시작' }));

    const result = await screen.findByTestId('bulk-retry-result');
    expect(result).toHaveTextContent('접수 2건');
    // 진행 확인 경로(영상별 처리 단계)를 함께 알린다.
    expect(within(result).getByTestId('bulk-retry-accepted-note')).toHaveTextContent('처리 단계');
    // 완료를 뜻하는 표현이 결과 본문에 없어야 한다.
    expect(result.textContent ?? '').not.toMatch(/완료|재시작했|시작했/);
  });

  it('실패한_영상은_선택으로_남아_다시_시도할_수_있다', async () => {
    mockVideos(mock, 2);
    mock.onPost('/videos/batch/retry').reply(200, {
      success: true,
      data: {
        successCount: 1,
        failureCount: 1,
        results: [
          { rawSn: 1, success: true, reason: null },
          { rawSn: 2, success: false, reason: '이미 재처리가 진행 중입니다.' },
        ],
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 배치 일괄 재시작' }));

    await screen.findByTestId('bulk-retry-result');
    await waitFor(() => {
      expect(screen.getByRole('checkbox', { name: 'CCTV-2 선택' })).toBeChecked();
    });
    expect(screen.getByRole('checkbox', { name: 'CCTV-1 선택' })).not.toBeChecked();
  });

  // [@design SCREEN-008] [@design API-199] [@design AC-1133] [@design AC-1134]
  // ★ 사용자 신고 「비식별 실패 영상을 선택해 일괄 재시작해도 아무 반응이 없다」의 회귀 가드.
  describe('★선두 비식별이 실패한 영상', () => {
    it('선택할_수_있고_일괄_재시작_요청에_그대로_실린다_배치_상태로_좁히지_않는다', async () => {
      mockVideos(mock, 2, deidentFailedRow);
      mock.onPost('/videos/batch/retry').reply(200, {
        success: true,
        data: {
          successCount: 2,
          failureCount: 0,
          results: [
            { rawSn: 1, success: true, reason: null },
            { rawSn: 2, success: true, reason: null },
          ],
        },
        message: null,
        errorCode: null,
      });

      renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
      const user = await selectFirstTwo();
      const button = screen.getByRole('button', { name: '2개 영상 배치 일괄 재시작' });
      expect(button).toBeEnabled();
      await user.click(button);

      await waitFor(() => {
        const call = mock.history.post.find((h) => h.url === '/videos/batch/retry');
        expect(call).toBeDefined();
        expect(JSON.parse(call!.data)).toEqual({ rawSns: [1, 2] });
      });
      expect(await screen.findByTestId('bulk-retry-result')).toHaveTextContent('접수 2건');
    });

    it('안내가_재시작_대상에_비식별이_실패한_영상을_포함하고_0건이라고_말하지_않는다', async () => {
      mockVideos(mock, 2, deidentFailedRow);

      renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
      await selectFirstTwo();

      const hint = await screen.findByTestId('bulk-scope-hint');
      expect(hint).toHaveTextContent('비식별이 실패한 영상');
      // 구 안내는 배치 상태로 세어 「배치가 실패한 0건만 재시작 대상」이라고 말했다.
      expect(hint).not.toHaveTextContent('0건만');
      expect(hint).not.toHaveTextContent('재시작 대상입니다');
    });

    it('한_건도_접수되지_않아도_결과_창을_열어_접수_0건과_건별_사유를_보인다', async () => {
      mockVideos(mock, 2, deidentFailedRow);
      mock.onPost('/videos/batch/retry').reply(200, {
        success: true,
        data: {
          successCount: 0,
          failureCount: 2,
          results: [
            { rawSn: 1, success: false, reason: '검수가 승인된 적이 있는 영상은 재시작할 수 없습니다.' },
            { rawSn: 2, success: false, reason: '진행 중인 비식별 위탁이 있어 재시작할 수 없습니다.' },
          ],
        },
        message: null,
        errorCode: null,
      });

      renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
      const user = await selectFirstTwo();
      await user.click(screen.getByRole('button', { name: '2개 영상 배치 일괄 재시작' }));

      const dialog = await screen.findByRole('dialog', { name: '일괄 재시작 접수 결과' });
      const result = within(dialog).getByTestId('bulk-retry-result');
      expect(result).toHaveTextContent('접수 0건');
      expect(result).toHaveTextContent('접수하지 못함 2건');
      // 숫자 0 만으로는 「아무 일도 없었다」로 읽힌다 — 문장으로 짚는다.
      expect(within(result).getByTestId('bulk-retry-none-accepted')).toHaveTextContent(
        '한 건도 접수되지 않았습니다',
      );
      expect(within(result).getByTestId('bulk-retry-failure-1')).toHaveTextContent(
        '검수가 승인된 적이 있는 영상은 재시작할 수 없습니다.',
      );
      expect(within(result).getByTestId('bulk-retry-failure-2')).toHaveTextContent(
        '진행 중인 비식별 위탁이 있어 재시작할 수 없습니다.',
      );
      // 접수된 것이 없으니 「순서대로 처리된다」 안내는 두지 않는다.
      expect(within(result).queryByTestId('bulk-retry-accepted-note')).not.toBeInTheDocument();
      // 두 건 모두 선택으로 남아 다시 시도할 수 있다.
      expect(screen.getByRole('checkbox', { name: 'CCTV-1 선택' })).toBeChecked();
      expect(screen.getByRole('checkbox', { name: 'CCTV-2 선택' })).toBeChecked();
    });

    it('일부만_접수되면_접수_0건_문장을_두지_않는다', async () => {
      mockVideos(mock, 2, deidentFailedRow);
      mock.onPost('/videos/batch/retry').reply(200, {
        success: true,
        data: {
          successCount: 1,
          failureCount: 1,
          results: [
            { rawSn: 1, success: true, reason: null },
            { rawSn: 2, success: false, reason: '같은 영상에 대한 요청이 이미 수락됐습니다.' },
          ],
        },
        message: null,
        errorCode: null,
      });

      renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
      const user = await selectFirstTwo();
      await user.click(screen.getByRole('button', { name: '2개 영상 배치 일괄 재시작' }));

      const result = await screen.findByTestId('bulk-retry-result');
      expect(result).toHaveTextContent('접수 1건');
      expect(within(result).queryByTestId('bulk-retry-none-accepted')).not.toBeInTheDocument();
      expect(within(result).getByTestId('bulk-retry-failure-2')).toHaveTextContent(
        '같은 영상에 대한 요청이 이미 수락됐습니다.',
      );
    });
  });

  it('상한_100건을_넘게_고르면_보내기_전에_알리고_버튼을_막는다', async () => {
    mockVideos(mock, 101);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    await user.click(screen.getByRole('checkbox', { name: '전체 선택' }));

    expect(await screen.findByTestId('bulk-retry-limit-notice')).toHaveTextContent('최대 100건');
    expect(screen.getByRole('button', { name: '101개 영상 배치 일괄 재시작' })).toBeDisabled();
    expect(mock.history.post.filter((h) => h.url === '/videos/batch/retry')).toHaveLength(0);
  });

  it('WORKER_에게는_일괄_재시작_버튼이_노출되지_않는다', async () => {
    useAuthStore.setState({
      token: 'dummy-test-token',
      claims: {
        sub: 'u-2',
        role: 'WORKER',
        channel: 'INTERNAL',
        exp: Math.floor(Date.now() / 1000) + 3600,
      },
    });
    mockVideos(mock, 2);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());

    expect(screen.queryByRole('button', { name: /일괄 재시작/ })).not.toBeInTheDocument();
  });
});
