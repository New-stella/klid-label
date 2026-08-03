import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPanel } from '@/features/augment/components/AugmentResultPanel';
import type { AugmentResult, AugmentResultState } from '@/features/augment/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * R2 회귀 — **비교 이미지가 0장인 "이유"를 사실대로 말한다**.
 *
 * 구 구현은 `framePairs.length` 만 보고 분기해 서로 성격이 전혀 다른 상태(생성 중 · 반입 중 ·
 * 신고 보류 · 생성 실패 · 취소 · 실삭제)를 전부 "프레임별 비교 결과는 **외부 연동 이후**
 * 표시됩니다" 한 문구로 뭉갰다. 외부 연동은 이미 끝났으므로 그 문구는 **사실도 아니었다**.
 *
 * BE 는 `resultState` 를 항상 내려준다(계약 정본 = `AugmentResultItemResponse.STATE_*`).
 */
describe('AugmentResultPanel resultState 문구', () => {
  let mock: MockAdapter;

  const base: AugmentResult = {
    id: 77,
    videoId: 201,
    cctvName: 'CCTV-07',
    type: 'WINTER',
    framePairs: [],
    totalFramePairs: 0,
    decision: 'PENDING',
    reviewable: true,
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

  const cases: { state: AugmentResultState; expected: string }[] = [
    { state: 'GENERATING', expected: '생성이 진행 중입니다' },
    { state: 'PREPARING_FRAMES', expected: '비교 이미지를 반입하고 있습니다' },
    { state: 'READY', expected: '표시할 비교 이미지가 없습니다' },
    { state: 'WITHHELD', expected: '비식별 누락 신고가 접수되어' },
    { state: 'GENERATION_FAILED', expected: '생성에 실패해' },
    { state: 'CANCELED', expected: '취소되어' },
    { state: 'PURGED', expected: '유예 기간이 지나 삭제되어' },
    // Phase 6 잔여 D-1 — 파생 매핑이 없는 그랜드퍼더링 항목. "반입 중"(곧 옴)으로 뭉개면
    // 영원히 오지 않을 것을 곧 온다고 말하게 된다.
    { state: 'DERIVATIVE_UNLINKED', expected: '이전에 생성된 결과물이라' },
  ];

  it.each(cases)(
    'resultState_별로_서로_다른_문구가_표시된다 [$state]',
    async ({ state, expected }) => {
      // given
      const result: AugmentResult = { ...base, resultState: state };

      // when
      renderWithProviders(
        <AugmentResultPanel result={result} framePage={0} onFramePageChange={vi.fn()} />,
      );

      // then
      const notice = await screen.findByTestId('augment-result-no-pairs-77');
      expect(notice).toHaveTextContent(expected);
      // 폐기된 구 문구가 남아 있으면 안 된다 (사실 아님)
      expect(document.body.textContent).not.toContain('외부 연동 이후');
    },
  );

  it('상태별_문구가_서로_겹치지_않는다', () => {
    // given/then — 같은 문구로 뭉개지 않았는지 (READY 는 폴백과 같아도 무방)
    const distinct = new Set(cases.map((c) => c.expected));
    expect(distinct.size).toBe(cases.length);
  });

  it('resultState_가_없는_구응답은_폴백_문구를_쓴다', async () => {
    // given — 구 서버 응답에는 resultState 필드 자체가 없다
    // when
    renderWithProviders(
      <AugmentResultPanel result={base} framePage={0} onFramePageChange={vi.fn()} />,
    );

    // then — 중립 폴백(사실이 아닌 "외부 연동 이후" 금지)
    const notice = await screen.findByTestId('augment-result-no-pairs-77');
    expect(notice).toHaveTextContent('표시할 비교 이미지가 없습니다');
    expect(document.body.textContent).not.toContain('외부 연동 이후');
  });

  it('알_수_없는_resultState_가_와도_화면이_깨지지_않는다', async () => {
    // given — BE 가 8번째 상태를 추가한 경우(계약 확장은 additive 로 온다)
    const result = { ...base, resultState: 'SOME_FUTURE_STATE' } as unknown as AugmentResult;

    // when
    renderWithProviders(
      <AugmentResultPanel result={result} framePage={0} onFramePageChange={vi.fn()} />,
    );

    // then — 런타임 예외 없이 폴백 문구
    const notice = await screen.findByTestId('augment-result-no-pairs-77');
    expect(notice).toHaveTextContent('표시할 비교 이미지가 없습니다');
  });

  it('해상도파생_항목에는_유예_안내가_뜨지_않는다', async () => {
    // given — 해상도 파생(RESL_*)은 폐기 체계 밖이라 discard 가 항상 null 이고
    //         검수 카드 자체가 노출되지 않는다
    const result: AugmentResult = {
      ...base,
      id: 78,
      type: 'RESL_720P',
      decision: 'ACCEPTED',
      reviewable: false,
      resultState: 'READY',
      discard: {
        discardedAt: '2026-07-25T09:00:00',
        purgeAt: '2026-08-01T09:00:00',
        purged: false,
        restorable: true,
      },
    };

    // when
    renderWithProviders(
      <AugmentResultPanel result={result} framePage={0} onFramePageChange={vi.fn()} />,
    );

    // then
    await screen.findByTestId('augment-result-no-pairs-78');
    expect(screen.queryByTestId('decision-card')).not.toBeInTheDocument();
    expect(screen.queryByTestId('decision-discard')).not.toBeInTheDocument();
  });

  it('반려_항목의_폐기_축이_결정_카드로_전달된다', async () => {
    // given — 반려 + 폐기 표식이 있는 외부 위탁 항목
    const result: AugmentResult = {
      ...base,
      id: 79,
      decision: 'REJECTED',
      rejectReason: '품질 미달',
      resultState: 'PREPARING_FRAMES',
      discard: {
        discardedAt: '2026-07-25T09:00:00',
        purgeAt: '2026-08-01T09:00:00',
        purged: false,
        restorable: true,
      },
    };

    // when
    renderWithProviders(
      <AugmentResultPanel result={result} framePage={0} onFramePageChange={vi.fn()} />,
    );

    // then
    expect(await screen.findByTestId('decision-discard')).toBeInTheDocument();
    expect(screen.getByTestId('decision-discard-purge-at')).toBeInTheDocument();
  });
});
