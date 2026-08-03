import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPage } from '@/pages/AugmentResultPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

/**
 * Phase 3 — 폐기(반려) 복구의 **화면 배선**.
 *
 * <h3>버튼 가시성은 BE 의 `restoreEligible` 이 정한다 (Critical, DEV_FIX HIGH-①)</h3>
 * 화면이 `decision`/`discard` 로 복구 가능 여부를 **재유도**하면 dead-letter 항목처럼
 * "BE 가 100% 404 를 내는데 버튼은 계속 떠 있는" 조합이 생긴다. 아래 dead-letter 테스트가
 * 그 재유도를 되돌리면 실패하도록 가드한다.
 *
 * <h3>실패 응답은 코드도 사유도 여러 갈래다 — 어느 축으로도 분기하지 않는다</h3>
 * BE `AugmentDiscardService` 실측:
 * - **404** "복구할 폐기 이력이 없습니다."(`restoreWithoutMark` — 되돌릴 결정 자체가 없음)
 * - **409** "복구할 검수 이력이 없습니다."(표식은 열려 있는데 스윕이 검수 행을 먼저 지운 레이스)
 * - **409** "유예 기간이 지나 이미 삭제된…"(실삭제 커밋과 경합)
 *
 * 셋 다 필요한 반응이 같다: BE 가 준 안내를 그대로 보여주고, 결과 쿼리를 무효화해
 * **서버 진실과 재동기화**한다. 상태 코드로도 **메시지 문자열로도** 분기하지 않는다(문구가 바뀌면
 * 화면이 조용히 깨진다).
 *
 * 낙관적 업데이트는 쓰지 않는다 — 쓰면 사라져야 할 버튼이 되돌아온다.
 */
describe('AugmentResultPage 폐기 복구', () => {
  let mock: MockAdapter;
  const AUG_ID = 7;

  type ResultOver = Record<string, unknown>;
  /** GET /result 응답을 호출 순서대로 바꿔 끼운다(무효화 후 재조회 결과를 표현). */
  let resultQueue: ResultOver[];

  const resultBody = (over: ResultOver) => ({
    success: true,
    data: {
      jobId: 101,
      status: 'COMPLETED',
      results: [
        {
          id: AUG_ID,
          videoId: 101,
          cctvName: 'CCTV-01',
          type: 'WINTER',
          framePairs: [],
          decision: 'REJECTED',
          decidedAt: '2026-07-25T09:00:00',
          rejectReason: '품질 미달',
          derivativeRawSn: null,
          totalFramePairs: 0,
          reviewable: true,
          prompt: null,
          resultState: 'READY',
          discard: {
            discardedAt: '2026-07-25T09:00:00',
            purgeAt: '2026-08-01T09:00:00',
            purged: false,
            restorable: true,
          },
          restoreEligible: true,
          ...over,
        },
      ],
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

  const renderPage = () =>
    renderWithProviders(
      <Routes>
        <Route path="/augment/result/:jobId" element={<AugmentResultPage />} />
      </Routes>,
      { initialEntries: ['/augment/result/101'] },
    );

  const submitRestore = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(await screen.findByTestId('decision-restore'));
    await user.type(await screen.findByRole('textbox'), '오판이라 되돌립니다');
    await user.click(screen.getByRole('button', { name: '복구 확정' }));
  };

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    resultQueue = [{}];
    mock.onGet('/augments/101/result').reply(() => {
      const over = resultQueue.length > 1 ? resultQueue.shift() : resultQueue[0];
      return [200, resultBody(over ?? {})];
    });
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
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useUiStore.setState({ toasts: [] });
  });

  it('복구_성공시_결과_쿼리가_무효화되고_다시_결정할_수_있다', async () => {
    // given — 복구 후 재조회에서는 활용 결정 대기로 돌아온다
    const user = userEvent.setup();
    resultQueue = [
      {},
      {
        decision: 'PENDING',
        decidedAt: null,
        rejectReason: null,
        discard: null,
        restoreEligible: false,
      },
    ];
    mock.onPost(`/augments/${AUG_ID}/restore`).reply(200, {
      success: true,
      data: { id: AUG_ID, decision: 'PENDING' },
      message: null,
      errorCode: null,
    });
    renderPage();

    // when
    await submitRestore(user);

    // then — 요청 바디는 사유만
    await waitFor(() => {
      expect(mock.history.post).toHaveLength(1);
    });
    expect(mock.history.post[0].url).toBe(`/augments/${AUG_ID}/restore`);
    expect(JSON.parse(mock.history.post[0].data ?? '{}')).toEqual({
      reason: '오판이라 되돌립니다',
    });

    // then — 무효화 → 재조회 → 다시 채택/거부를 고를 수 있다
    await waitFor(() => {
      expect(screen.getByTestId('decision-card')).toHaveAttribute(
        'data-decision',
        'PENDING',
      );
    });
    expect(screen.getByRole('button', { name: '채택' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '거부' })).toBeInTheDocument();
  });

  /**
   * **404** — "복구할 폐기 이력이 없습니다."
   *
   * ⚠ 이 메시지는 `ErrorCode.NOT_FOUND`(=404)다. 구 테스트는 이것을 **409 로 목킹**해 BE 가 내지
   * 않는 계약을 고정했다(실제 409 는 다른 메시지다 — 아래 별도 테스트). 응답 코드를 실제와 맞춰
   * "코드로 분기하지 않는다" 는 계약이 진짜로 성립하는지 본다.
   *
   * 시나리오: 화면이 stale 한 `restoreEligible=true` 를 들고 있었고 실제로는 되돌릴 결정이 없었다
   * → 404 → 재동기화로 버튼이 사라진다(재시도 루프 차단).
   */
  it('복구_404_폐기이력없음이면_서버_안내가_표시되고_재동기화로_버튼이_사라진다', async () => {
    // given
    const user = userEvent.setup();
    const BE_MESSAGE = '복구할 폐기 이력이 없습니다.';
    resultQueue = [
      {},
      // 재조회 결과 = 되돌릴 결정이 없는 항목(생성 영구 실패). BE 가 restoreEligible=false 로 말한다.
      {
        resultState: 'GENERATION_FAILED',
        discard: null,
        restoreEligible: false,
      },
    ];
    mock.onPost(`/augments/${AUG_ID}/restore`).reply(404, {
      success: false,
      data: null,
      message: BE_MESSAGE,
      errorCode: 'NOT_FOUND',
    });
    renderPage();
    await screen.findByTestId('decision-restore');
    const before = mock.history.get.filter(
      (r) => r.url === '/augments/101/result',
    ).length;

    // when
    await submitRestore(user);

    // then — BE 문구를 그대로 노출한다(FE 가 문구를 지어내지 않는다)
    await waitFor(() => {
      expect(useUiStore.getState().toasts).toHaveLength(1);
    });
    expect(useUiStore.getState().toasts[0]?.message).toBe(BE_MESSAGE);
    expect(useUiStore.getState().toasts[0]?.variant).toBe('error');

    // then — 서버 진실과 재동기화
    await waitFor(() => {
      const after = mock.history.get.filter(
        (r) => r.url === '/augments/101/result',
      ).length;
      expect(after).toBeGreaterThan(before);
    });
    // then — 영구 실패이므로 버튼이 남아 무한 재시도가 되면 안 된다
    await waitFor(() => {
      expect(screen.queryByTestId('decision-restore')).not.toBeInTheDocument();
    });
  });

  /**
   * **409** — "복구할 검수 이력이 없습니다."
   *
   * 폐기 표식은 열려 있는데(`findOpenForUpdate` 성공) 스윕이 **검수 행을 먼저** 지운 레이스에서
   * `AugmentDiscardService.restore` 가 내는 유일한 409 다. 구 테스트는 이 문구를 한 번도 다루지
   * 않았다(404 문구를 409 로 목킹하고 있었다).
   */
  it('복구_409_검수이력없음이면_서버_안내가_그대로_표시되고_화면이_재동기화된다', async () => {
    // given
    const user = userEvent.setup();
    const BE_MESSAGE = '복구할 검수 이력이 없습니다.';
    mock.onPost(`/augments/${AUG_ID}/restore`).reply(409, {
      success: false,
      data: null,
      message: BE_MESSAGE,
      errorCode: 'CONFLICT',
    });
    renderPage();
    await screen.findByTestId('decision-restore');
    const before = mock.history.get.filter(
      (r) => r.url === '/augments/101/result',
    ).length;

    // when
    await submitRestore(user);

    // then — 상태 코드로도 메시지로도 분기하지 않고 서버 안내를 그대로 낸다
    await waitFor(() => {
      expect(useUiStore.getState().toasts).toHaveLength(1);
    });
    expect(useUiStore.getState().toasts[0]?.message).toBe(BE_MESSAGE);
    expect(useUiStore.getState().toasts[0]?.variant).toBe('error');

    // then — 서버 진실과 재동기화
    await waitFor(() => {
      const after = mock.history.get.filter(
        (r) => r.url === '/augments/101/result',
      ).length;
      expect(after).toBeGreaterThan(before);
    });
  });

  /**
   * ★ **판별력 테스트** — 가시성 재유도를 되돌리면 이 테스트가 실패해야 한다.
   *
   * dead-letter 항목의 형상: `decision=REJECTED`(생성 실패를 실패로 보인다) + `discard=null`
   * (표식 없음) + `resultState=GENERATION_FAILED`. 구 조건
   * `decision === 'REJECTED' && discard?.purged !== true` 는 **참**이라 버튼을 그렸고, BE 는
   * 이 항목에 100% 404 를 낸다(검수 행도 표식도 없어 `restoreWithoutMark` 가 거부).
   *
   * 화면은 같은 항목에 "생성에 실패해 비교 이미지가 만들어지지 않았습니다" 를 띄우면서 그 옆에
   * 복구 버튼을 그리고 있었다 — 그 자기모순을 여기서 고정한다.
   */
  it('생성_영구실패_항목에는_복구_버튼이_처음부터_뜨지_않는다', async () => {
    // given — BE 가 restoreEligible=false 로 "복구 API 가 받지 않는다" 를 직접 말한다
    resultQueue = [
      {
        resultState: 'GENERATION_FAILED',
        discard: null,
        restoreEligible: false,
      },
    ];
    renderPage();

    // when — 거부됨 카드가 렌더될 때까지 기다린다
    await waitFor(() => {
      expect(screen.getByTestId('decision-card')).toHaveAttribute(
        'data-decision',
        'REJECTED',
      );
    });

    // then — 누르면 반드시 404 인 버튼을 그리지 않는다
    expect(screen.queryByTestId('decision-restore')).not.toBeInTheDocument();
    // then — 복구 요청이 나갈 수 없다
    expect(mock.history.post).toHaveLength(0);
  });

  it('복구_409_이미삭제면_서버_안내가_표시되고_복구_버튼이_사라진다', async () => {
    // given — (b) 레이스: 버튼을 그린 뒤 스윕이 실삭제를 커밋했다
    const user = userEvent.setup();
    const BE_MESSAGE =
      '유예 기간이 지나 이미 삭제된 파생영상입니다. 필요하면 증강을 다시 요청해 주세요.';
    resultQueue = [
      {},
      {
        resultState: 'PURGED',
        discard: {
          discardedAt: '2026-07-25T09:00:00',
          purgeAt: '2026-08-01T09:00:00',
          purged: true,
          restorable: false,
        },
        restoreEligible: false,
      },
    ];
    mock.onPost(`/augments/${AUG_ID}/restore`).reply(409, {
      success: false,
      data: null,
      message: BE_MESSAGE,
      errorCode: 'CONFLICT',
    });
    renderPage();

    // when
    await submitRestore(user);

    // then
    await waitFor(() => {
      expect(useUiStore.getState().toasts[0]?.message).toBe(BE_MESSAGE);
    });
    // 재동기화 결과로 버튼이 사라진다 — 낙관적 업데이트로 되살아나지 않는다
    await waitFor(() => {
      expect(screen.queryByTestId('decision-restore')).not.toBeInTheDocument();
    });
  });

  it('복구_연타해도_1회만_전송된다', async () => {
    // given — 연타 방어는 FE 단독 책임
    const user = userEvent.setup();
    mock.onPost(`/augments/${AUG_ID}/restore`).reply(200, {
      success: true,
      data: { id: AUG_ID, decision: 'PENDING' },
      message: null,
      errorCode: null,
    });
    renderPage();
    await user.click(await screen.findByTestId('decision-restore'));
    await user.type(await screen.findByRole('textbox'), '오판이라 되돌립니다');
    const confirm = screen.getByRole('button', { name: '복구 확정' });

    // when — 같은 tick 에 연속 클릭
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    fireEvent.click(confirm);

    // then
    await waitFor(() => {
      expect(mock.history.post).toHaveLength(1);
    });
  });
});
