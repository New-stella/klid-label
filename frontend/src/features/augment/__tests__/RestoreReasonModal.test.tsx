import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { RestoreReasonModal } from '../components/RestoreReasonModal';

/**
 * Phase 3 — 폐기(반려) 복구 사유 입력 모달.
 *
 * BE 계약은 `@NotBlank` + `@Size(max=500)` 다. `@NotBlank` 는 **공백만도 거부**하므로 화면도
 * 같은 제약을 걸어야 한다 — 기존 거부 사유 모달의 `min(1)` 은 `" "` 한 글자를 통과시켜
 * 사용자가 전송 후에야 400 을 보게 된다(같은 갭을 복제하지 않는다).
 *
 * 이 모달은 거부 모달과 **의미가 반대**라 재사용하지 않는다(문구·강조색이 모두 반대).
 */
describe('RestoreReasonModal 사유 검증', () => {
  // 처리 중에는 스피너의 대체 텍스트가 접근가능 이름에 섞이므로 정규식으로 찾는다.
  const SUBMIT = /복구 확정/;

  it('복구_사유가_공백만이면_제출할_수_없다', async () => {
    // given
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <RestoreReasonModal open onClose={() => {}} onConfirm={onConfirm} />,
    );

    // when — 공백만 입력
    await user.type(screen.getByRole('textbox'), '   ');

    // then
    expect(screen.getByRole('button', { name: SUBMIT })).toBeDisabled();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('복구_사유가_500자를_넘으면_제출할_수_없다', async () => {
    // given
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <RestoreReasonModal open onClose={() => {}} onConfirm={onConfirm} />,
    );

    // when — 501자
    await user.click(screen.getByRole('textbox'));
    await user.paste('가'.repeat(501));

    // then
    expect(screen.getByRole('button', { name: SUBMIT })).toBeDisabled();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('정상_사유를_입력하면_onConfirm_에_전달된다', async () => {
    // given
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <RestoreReasonModal open onClose={() => {}} onConfirm={onConfirm} />,
    );

    // when
    await user.type(screen.getByRole('textbox'), '오판이라 되돌립니다');
    await user.click(screen.getByRole('button', { name: SUBMIT }));

    // then
    expect(onConfirm).toHaveBeenCalledWith('오판이라 되돌립니다');
  });

  /**
   * **연타 락(`submitLockRef`)의 직접 검증** — DEV_FIX 2차 ④.
   *
   * 이 락은 연타 방어의 **유일한 방어선**이다(서버측 중복 차단·속도 제한을 두지 않는 확정 정책).
   * 그런데 페이지 레벨 연타 테스트는 판별력이 **0** 이다 — `onConfirm` 이 모달을 언마운트해
   * 2·3번째 클릭이 detached 노드에 떨어지므로 **락과 `disabled` 를 둘 다 지워도 통과**한다.
   *
   * 그래서 모달이 **마운트된 채**, `loading` 도 바뀌지 않는(부모 없는 단위) 조건에서 같은 tick 에
   * 클릭을 몰아넣는다. react-hook-form 의 `handleSubmit` 은 비동기라 세 submit 이 모두 검증까지
   * 도달한 뒤에야 첫 요청의 상태 변화가 반영된다 — 버튼 `disabled` 는 이미 늦고, 락이 없으면
   * `onConfirm` 이 그대로 3회 불린다(복구 요청이 3번 나간다).
   */
  it('같은_tick_에_연타해도_onConfirm_은_한_번만_불린다', async () => {
    // given
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <RestoreReasonModal open onClose={() => {}} onConfirm={onConfirm} />,
    );
    await user.type(screen.getByRole('textbox'), '오판이라 되돌립니다');
    const submit = screen.getByRole('button', { name: SUBMIT });

    // when — await 없이 동기 연타(같은 tick). userEvent 는 클릭마다 await 하므로 여기선 쓰지 않는다.
    fireEvent.click(submit);
    fireEvent.click(submit);
    fireEvent.click(submit);

    // then
    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));
    expect(onConfirm).toHaveBeenCalledWith('오판이라 되돌립니다');
  });

  it('처리중에는_제출_버튼이_비활성이다', async () => {
    // given — 연타 방어는 FE 단독 책임(서버측 중복 차단 없음)
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <RestoreReasonModal open loading onClose={() => {}} onConfirm={onConfirm} />,
    );

    // when
    await user.type(screen.getByRole('textbox'), '정상 사유');

    // then
    expect(screen.getByRole('button', { name: SUBMIT })).toBeDisabled();
  });
});
