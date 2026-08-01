import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { DecisionCard } from '../components/DecisionCard';
import type { AugmentDecision, AugmentDiscardState } from '../types';

/**
 * Phase 3 — 폐기(반려)된 결과물의 **복구 동선**.
 *
 * <h3>가시성 축 = BE 의 `restoreEligible` **하나** (Critical, DEV_FIX HIGH-①)</h3>
 * 구 구현은 `decision === 'REJECTED' && !discard.purged` 로 **재유도**했다. 그런데 BE 의
 * `REJECTED` 는 세 입력에서 나온다 — ①사람의 반려 ②폐기 표식 ③**생성 영구 실패(dead-letter)**.
 * 복구 API 는 ①②만 받고 ③은 검수 행도 표식도 없어 **항상 404** 라, 화면은 누르면 반드시 실패하는
 * 버튼을 그리고 재조회해도 값이 그대로라 **무한 재시도**가 됐다(서버측 중복 차단이 없는 정책).
 *
 * 그래서 이 카드는 BE 가 자기 사전조건으로 계산해 내려준 값을 **그대로** 쓴다.
 * `decision`/`resultState`/`discard` 로 재유도하지 않는다.
 *
 * ⚠ `discard.restorable` 은 **다른 축**이다 — 폐기 *표식* 수준 힌트라 표식이 없는 반려
 * (그랜드퍼더링)를 표현할 수 없고, 실삭제 클레임 구간에서 보수적으로 false 를 낸다. 두 값이
 * 갈리는 것이 정상이며 어느 한쪽으로 통일하지 않는다.
 */
describe('DecisionCard 폐기 복구', () => {
  const discard: AugmentDiscardState = {
    discardedAt: '2026-07-25T09:00:00',
    purgeAt: '2026-08-01T09:00:00',
    purged: false,
    restorable: true,
  };

  const renderCard = (over: Partial<React.ComponentProps<typeof DecisionCard>> = {}) =>
    render(
      <DecisionCard
        status="REJECTED"
        rejectReason="품질 미달"
        discard={discard}
        restoreEligible
        onAccept={() => {}}
        onReject={() => {}}
        onRestore={() => {}}
        {...over}
      />,
    );

  it('BE가_복구가능이라고_하면_복구_버튼이_보인다', () => {
    // given/when
    renderCard();

    // then
    expect(screen.getByTestId('decision-restore')).toBeInTheDocument();
  });

  /**
   * ★ 이 결함의 재현 케이스 — **생성 영구 실패(dead-letter)**.
   *
   * `decision=REJECTED`(실패를 실패로 보인다) + `discard=null`(표식 없음) 조합이라, 구 구현의
   * 재유도 조건은 **참**이 되어 버튼을 그렸다. BE 는 이 항목에 `restoreEligible=false` 를 실어
   * "복구 API 가 받지 않는다" 를 직접 말한다.
   *
   * `resultState=GENERATION_FAILED` 를 함께 준 것은 **화면이 그 값으로 재유도하지 않는지** 를
   * 보기 위함이 아니라(카드는 resultState 를 받지도 않는다) 재현 형상을 정확히 옮기기 위함이다.
   */
  it('생성_영구실패_항목에는_복구_버튼이_보이지_않는다', () => {
    // given — dead-letter: decision 은 REJECTED 지만 되돌릴 결정이 없다(BE 는 항상 404)
    renderCard({ discard: null, restoreEligible: false });

    // then
    expect(screen.queryByTestId('decision-restore')).not.toBeInTheDocument();
  });

  it('이미_삭제된_항목에는_복구_버튼이_보이지_않는다', () => {
    // given — 유예 경과로 실삭제 커밋됨(되돌릴 대상이 없다 → BE restoreEligible=false)
    renderCard({
      discard: { ...discard, purged: true, restorable: false },
      restoreEligible: false,
    });

    // then
    expect(screen.queryByTestId('decision-restore')).not.toBeInTheDocument();
  });

  it('폐기_표식이_없는_반려_항목에도_복구_버튼이_보인다', () => {
    // given — 그랜드퍼더링: 폐기 원장 행이 없어도 BE 는 검수 행 재오픈으로 복구한다
    //         (BE 가 그 사실을 restoreEligible=true 로 알려준다)
    renderCard({ discard: null, restoreEligible: true });

    // then
    expect(screen.getByTestId('decision-restore')).toBeInTheDocument();
  });

  it('폐기표식_힌트가_false_여도_BE가_복구가능이면_버튼은_보인다', () => {
    // given — 실삭제 클레임 구간: discard.restorable 은 보수적으로 false 지만 복구는 성립한다.
    //         두 축이 갈리는 것이 정상이며, 가시성은 restoreEligible 만 본다.
    renderCard({ discard: { ...discard, restorable: false }, restoreEligible: true });

    // then
    expect(screen.getByTestId('decision-restore')).toBeInTheDocument();
  });

  it('BE가_값을_주지_않으면_복구_버튼을_그리지_않는다', () => {
    // given — 구 BE 응답(필드 없음). 반드시 실패하는 버튼보다 없는 편이 낫다(fail-closed).
    renderCard({ restoreEligible: undefined });

    // then
    expect(screen.queryByTestId('decision-restore')).not.toBeInTheDocument();
  });

  it.each<AugmentDecision>(['PENDING', 'ACCEPTED', 'CANCELED'])(
    '반려가_아닌_항목에는_복구_버튼이_보이지_않는다 [%s]',
    (status) => {
      // given/when
      renderCard({ status, discard });

      // then
      expect(screen.queryByTestId('decision-restore')).not.toBeInTheDocument();
    },
  );

  it('복구_사유를_입력해야_onRestore_가_호출된다', async () => {
    // given
    const user = userEvent.setup();
    const onRestore = vi.fn();
    renderCard({ onRestore });

    // when — 버튼만 눌렀을 때는 전송되지 않는다
    await user.click(screen.getByTestId('decision-restore'));
    expect(onRestore).not.toHaveBeenCalled();

    // when — 모달에서 사유 입력 후 확정
    await user.type(screen.getByRole('textbox'), '오판이라 되돌립니다');
    await user.click(screen.getByRole('button', { name: '복구 확정' }));

    // then
    expect(onRestore).toHaveBeenCalledWith('오판이라 되돌립니다');
  });

  it('복구_요청_중에는_버튼이_비활성이다', () => {
    // given — 연타 방어는 FE 단독 책임(서버측 중복 차단·속도 제한을 두지 않는 정책)
    renderCard({ loading: true });

    // then
    expect(screen.getByTestId('decision-restore')).toBeDisabled();
  });
});
