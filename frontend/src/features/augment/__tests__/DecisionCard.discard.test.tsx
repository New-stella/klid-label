import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { DecisionCard } from '../components/DecisionCard';
import type { AugmentDiscardState } from '../types';

/**
 * R4 회귀 — **반려(폐기)된 결과물의 유예 안내**.
 *
 * 반려는 소프트 삭제이고 유예가 지나면 배치가 실삭제한다. 그 사실을 화면이 말하지 않으면
 * REVIEWER 는 "언제까지 되돌릴 수 있는지" 를 알 수 없다.
 *
 * ⚠ 폐기 스윕은 주기 배치(기본 1시간)라 `purgeAt` 이 지나도 한동안 살아 있는 것이 **정상**이다.
 *   따라서 "정확히 그때 지워진다" 고 단언하지 않고 **예정**임이 드러나야 한다.
 * ⚠ `purgeAt === null` 은 폐기 스윕 비활성 — 영원히 안 지워질 수 있으므로 **거짓 예정 시각 금지**.
 */
describe('DecisionCard 폐기 유예 안내', () => {
  const PURGE_AT = '2026-08-01T09:00:00';
  const discard: AugmentDiscardState = {
    discardedAt: '2026-07-25T09:00:00',
    purgeAt: PURGE_AT,
    purged: false,
    restorable: true,
  };

  it('반려항목에_실삭제_예정일시가_표시된다', () => {
    // given/when
    render(
      <DecisionCard
        status="REJECTED"
        decidedAt="2026-07-25T09:00:00"
        rejectReason="품질 미달"
        discard={discard}
        onAccept={() => {}}
        onReject={() => {}}
      />,
    );

    // then
    const notice = screen.getByTestId('decision-discard');
    const purgeAt = screen.getByTestId('decision-discard-purge-at');
    expect(purgeAt).toHaveTextContent(new Date(PURGE_AT).toLocaleString('ko-KR'));
    // "정확히 그 시각에 지워진다" 로 읽히면 안 된다 — 예정임이 드러나야 한다
    expect(notice).toHaveTextContent('예정');
  });

  it('purgeAt_이_null_이면_예정일시를_표시하지_않는다', () => {
    // given — 폐기 스윕 비활성
    render(
      <DecisionCard
        status="REJECTED"
        rejectReason="품질 미달"
        discard={{ ...discard, purgeAt: null }}
        onAccept={() => {}}
        onReject={() => {}}
      />,
    );

    // then — 안내는 있되 거짓 예정 시각은 없다
    expect(screen.getByTestId('decision-discard')).toBeInTheDocument();
    expect(screen.queryByTestId('decision-discard-purge-at')).not.toBeInTheDocument();
  });

  it('이미_삭제된_항목은_유예_안내_대신_삭제됨을_표시한다', () => {
    // given
    render(
      <DecisionCard
        status="REJECTED"
        rejectReason="품질 미달"
        discard={{ ...discard, purged: true, restorable: false }}
        onAccept={() => {}}
        onReject={() => {}}
      />,
    );

    // then
    const notice = screen.getByTestId('decision-discard');
    expect(notice).toHaveTextContent('삭제');
    expect(notice).toHaveAttribute('data-purged', 'true');
    expect(screen.queryByTestId('decision-discard-purge-at')).not.toBeInTheDocument();
  });

  it('discard_가_없으면_유예_안내를_그리지_않는다', () => {
    // given — 그랜드퍼더링(폐기 표식 없음) 또는 복구되어 지금은 폐기 상태가 아님
    render(
      <DecisionCard
        status="REJECTED"
        rejectReason="품질 미달"
        onAccept={() => {}}
        onReject={() => {}}
      />,
    );

    // then
    expect(screen.queryByTestId('decision-discard')).not.toBeInTheDocument();
  });

  it('반려가_아닌_상태에는_유예_안내를_그리지_않는다', () => {
    // given — 채택된 항목에 폐기 축이 실려 와도 표시 대상이 아니다
    render(
      <DecisionCard
        status="ACCEPTED"
        decidedAt="2026-07-25T09:00:00"
        discard={discard}
        onAccept={() => {}}
        onReject={() => {}}
      />,
    );

    // then
    expect(screen.queryByTestId('decision-discard')).not.toBeInTheDocument();
  });

  it('복구_버튼은_이번_범위가_아니라_그리지_않는다', () => {
    // given — 복구 동선은 Phase 3. 지금 그리면 누를 곳이 없는 버튼이 된다.
    render(
      <DecisionCard
        status="REJECTED"
        rejectReason="품질 미달"
        discard={discard}
        onAccept={() => {}}
        onReject={() => {}}
      />,
    );

    // then
    expect(screen.queryByRole('button', { name: /복구/ })).not.toBeInTheDocument();
  });
});
