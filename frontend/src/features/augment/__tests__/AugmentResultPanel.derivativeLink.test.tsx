import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPanel } from '@/features/augment/components/AugmentResultPanel';
import type { AugmentResult } from '@/features/augment/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * ★파생 영상으로 가는 링크는 **두지 않는다** — 폐기 확정(사양 SCREEN-023, 사용자 확정).
 *
 * <h3>왜 성립하지 않는 기능인가</h3>
 * 증강은 이미지 대 이미지라 파생 영상 파일은 원본(비식별) 영상을 **그대로 복사한 것**이고 바뀐
 * 것은 **프레임 이미지뿐**이다. 그 영상을 재생하면 증강 전 원본이 나오므로 「생성된 파생 영상
 * 보기」는 결과 확인 수단이 되지 못한다. 저작도구는 파생영상을 재생·마킹하지 않으며 그 유일한
 * 소비자는 관제서버다. 결과 확인은 **프레임 비교 그리드와 확대 비교 창**이 전부 담당한다.
 *
 * <h3>이 파일이 음성 가드인 이유</h3>
 * 구 구현은 `derivativeRawSn` 이 있으면 `/video/{rawSn}` 링크를 그렸고, 이 파일은 그 링크의
 * 목적지·새 탭 속성·죽은 링크 차단(실삭제 · 매핑 부재)을 검증하는 **양성 가드**였다. 기능이
 * 폐기됐으므로 같은 조건에서 링크가 **그려지지 않는다**를 고정한다 — 조건을 지우고 파일을 없애면
 * 다음 사람이 "빠뜨린 기능" 으로 보고 되살린다.
 *
 * ⚠ 응답 필드 `derivativeRawSn` 자체는 BE 계약이라 그대로 둔다. 지운 것은 이 화면의 링크 렌더뿐이며,
 * 그래서 아래 케이스들은 그 필드를 **실어 보낸 채로** 링크 부재를 확인한다.
 */
describe('AugmentResultPanel 파생 영상 이동 링크 (폐기 — 렌더되지 않는다)', () => {
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

  it('derivativeRawSn_이_유효해도_파생영상_링크를_그리지_않는다', () => {
    // given — 구 구현이라면 `/video/512` 링크를 그렸을 조건
    renderPanel({ ...base, derivativeRawSn: 512 });

    // then — 링크도, 그 문구도 없다
    expect(screen.queryByTestId('augment-derivative-link')).not.toBeInTheDocument();
    expect(screen.queryByText(/생성된 영상 상세 보기/)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /영상 상세/ })).not.toBeInTheDocument();
  });

  it('폐기_유예중인_항목에도_파생영상_링크가_없다', () => {
    // given — 구 구현은 실삭제 전(purged=false)이면 링크를 그렸다
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
    expect(screen.queryByTestId('augment-derivative-link')).not.toBeInTheDocument();
  });

  it('실삭제된_항목에도_파생영상_링크가_없다', () => {
    // given — 구 구현도 이 창은 막았다. 폐기 이후에도 그 결과는 같아야 한다.
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

    // then — 폐기 안내는 그대로 뜨고 링크만 없다
    expect(screen.getByTestId('decision-discard')).toHaveAttribute('data-purged', 'true');
    expect(screen.queryByTestId('augment-derivative-link')).not.toBeInTheDocument();
  });

  /**
   * 링크 제거가 **다른 표시를 함께 지우지 않았는지** 확인한다 — 제거 범위를 링크 블록으로
   * 한정했다는 사실을 코드가 아니라 화면으로 고정한다.
   */
  it('링크가_사라져도_진행_패널_생성조건_프레임비교_결정카드는_그대로다', async () => {
    // given — 프레임 쌍 1건 + 생성 조건 + 결정 카드가 모두 있는 항목
    mock.onGet(/\/augments\/\d+\/progress$/).reply(200, {
      success: true,
      data: { total: 1, done: 1, failed: 0, state: 'READY' },
      message: null,
      errorCode: null,
    });
    renderPanel({
      ...base,
      derivativeRawSn: 512,
      prompt: '{"time":"낮","season":"겨울","weather":"눈","terrain":"도심","severity":"강"}',
      framePairs: [
        {
          srcSn: 9001,
          frameNo: 1,
          originalUrl: '/frames/9001/image',
          augmentedUrl: '/frames/9001/aug',
        },
      ],
      totalFramePairs: 1,
    });

    // then — 패널 골격이 그대로 남아 있다
    expect(screen.getByTestId('augment-result-item-77')).toBeInTheDocument();
    // 진행 패널 · 생성 조건 요약
    expect(await screen.findByTestId('augment-progress-77')).toBeInTheDocument();
    expect(screen.getByTestId('augment-prompt-77')).toBeInTheDocument();
    // 프레임 비교 영역(그리드)이 살아 있다 — 결과 확인의 유일한 수단이라 특히 중요하다
    expect(screen.getByTestId('frame-grid-12')).toBeInTheDocument();
    expect(screen.getByTestId('frame-pair-9001')).toBeInTheDocument();
    expect(screen.queryByTestId('augment-result-no-pairs-77')).not.toBeInTheDocument();
    // 결정 카드(채택/반려)도 그대로
    expect(screen.getByTestId('decision-card')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '채택' })).toBeInTheDocument();
    // 그리고 그 사이 어디에도 파생영상 링크는 없다
    expect(screen.queryByTestId('augment-derivative-link')).not.toBeInTheDocument();
  });
});
