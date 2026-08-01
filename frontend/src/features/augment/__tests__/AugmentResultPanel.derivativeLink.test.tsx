import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPanel } from '@/features/augment/components/AugmentResultPanel';
import type { AugmentResult } from '@/features/augment/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Phase 3 — 파생 영상으로 가는 **이동 링크**.
 *
 * 결과 화면에서 "만들어진 파생 영상 자체" 로 갈 수단이 없어 REVIEWER 가 주소를 직접 조립해야 했다.
 *
 * - 목적지는 rawSn 기반 영상 상세(`/video/{rawSn}`).
 * - 매핑이 없는 항목(그랜드퍼더링 · 생성 실패)은 **오류가 아니라 원래 없는 것**이므로 링크를
 *   그리지 않는다(죽은 링크를 만들지 않는다).
 * - **실삭제된 항목도 그리지 않는다** — BE 는 `newRawSn` 을 폐기 여부와 무관하게 싣는데 실삭제는
 *   `LS_DATA_RAW` 행 자체를 지우므로 그 링크는 404 다. 같은 BE 메서드가 정확히 이 창을 위해
 *   프레임 쌍을 비우고 있고(죽은 이미지 링크 차단), 화면도 "삭제되어 복구할 수 없습니다" 바로 위에
 *   "생성된 영상 상세 보기" 를 그리면 자기모순이 된다(DEV_FIX MEDIUM-③).
 * - 검수 작업 흐름이 끊기지 않도록 새 탭으로 열되 `rel="noopener noreferrer"` 를 붙인다(CWE-1022).
 */
describe('AugmentResultPanel 파생 영상 이동 링크', () => {
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
    resultState: 'READY',
  };

  const renderPanel = (result: AugmentResult) =>
    renderWithProviders(
      <AugmentResultPanel result={result} framePage={0} onFramePageChange={() => {}} />,
    );

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

  it('derivativeRawSn_이_있으면_파생영상_상세로_가는_링크가_새_탭으로_렌더된다', () => {
    // given/when
    renderPanel({ ...base, derivativeRawSn: 512 });

    // then
    const link = screen.getByTestId('augment-derivative-link');
    expect(link).toHaveAttribute('href', '/video/512');
    expect(link).toHaveAttribute('target', '_blank');
    // 탭 하이재킹 방어 (CWE-1022)
    expect(link.getAttribute('rel') ?? '').toContain('noopener');
    expect(link.getAttribute('rel') ?? '').toContain('noreferrer');
  });

  it('derivativeRawSn_이_없으면_링크가_렌더되지_않는다', () => {
    // given — 그랜드퍼더링 항목엔 매핑 자체가 없다
    renderPanel(base);

    // then
    expect(screen.queryByTestId('augment-derivative-link')).not.toBeInTheDocument();
  });

  it('derivativeRawSn_이_유효하지_않으면_링크가_렌더되지_않는다', () => {
    // given — BE 가 null 로 내려주는 필드라 0/음수 같은 값도 죽은 링크로 만들지 않는다
    renderPanel({ ...base, derivativeRawSn: 0 });

    // then
    expect(screen.queryByTestId('augment-derivative-link')).not.toBeInTheDocument();
  });

  it('derivativeRawSn_이_null_이면_링크가_렌더되지_않는다', () => {
    // given — BE 계약상 실제로 오는 값(매핑 없는 항목)
    renderPanel({ ...base, derivativeRawSn: null });

    // then
    expect(screen.queryByTestId('augment-derivative-link')).not.toBeInTheDocument();
  });

  /**
   * ★ 실삭제분 회귀 가드 — `derivativeRawSn > 0` 만 보면 **죽은 링크**가 남는다.
   *
   * 실삭제는 파생 `LS_DATA_RAW` 행을 실제로 지우므로 `/video/{rawSn}` 은 404 다. 그런데 BE 는
   * `newRawSn` 을 폐기 여부와 무관하게 실어 보낸다 — 판정은 BE 가 이미 말해준 사실
   * (`discard.purged`)로 한다.
   */
  it('실삭제된_항목에는_파생영상_링크를_그리지_않는다', () => {
    // given — 유예 경과로 실삭제 커밋됨. BE 는 newRawSn 을 계속 싣는다.
    renderPanel({
      ...base,
      decision: 'REJECTED',
      derivativeRawSn: 512,
      resultState: 'PURGED',
      restoreEligible: false,
      discard: {
        discardedAt: '2026-07-20T09:00:00',
        purgeAt: '2026-07-27T09:00:00',
        purged: true,
        restorable: false,
      },
    });

    // then — "삭제되어 복구할 수 없습니다" 안내와 링크가 공존하면 자기모순이다
    expect(screen.getByTestId('decision-discard')).toHaveAttribute(
      'data-purged',
      'true',
    );
    expect(screen.queryByTestId('augment-derivative-link')).not.toBeInTheDocument();
  });

  it('폐기_유예중인_항목에는_파생영상_링크가_그대로_보인다', () => {
    // given — 아직 실재하므로 이동할 수 있어야 한다(purged 만 막는다 — 폐기 전체를 막지 않는다)
    renderPanel({
      ...base,
      decision: 'REJECTED',
      derivativeRawSn: 512,
      restoreEligible: true,
      discard: {
        discardedAt: '2026-07-25T09:00:00',
        purgeAt: '2026-08-01T09:00:00',
        purged: false,
        restorable: true,
      },
    });

    // then
    expect(screen.getByTestId('augment-derivative-link')).toHaveAttribute(
      'href',
      '/video/512',
    );
  });
});
