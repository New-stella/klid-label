// UI-052 FrameNavigator 계약 테스트.
//
// 사양 3부 — ①처음/이전/다음/마지막 ②번호 직접 입력(Enter) ③위치 슬라이더(throttle + release flush).
// ★세 진입점이 모두 **단일 콜백 onRequestGoTo** 로 수렴해야 한다 — 진입점마다 미저장 가드를
//   따로 붙이면 한 곳이 샌다. 이 파일의 핵심 가드다.

import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { FrameNavigator } from '../FrameNavigator';

function setup(
  overrides: Partial<Omit<Parameters<typeof FrameNavigator>[0], 'onRequestGoTo'>> = {},
) {
  const onRequestGoTo = vi.fn<[number], void>();
  render(
    <FrameNavigator frameIndex={2} frameCount={10} onRequestGoTo={onRequestGoTo} {...overrides} />,
  );
  return { onRequestGoTo };
}

/** 호출 인자만 뽑아 순서까지 비교 — "몇 번 보냈고 마지막이 무엇인가"가 계약이다. */
function calls(fn: ReturnType<typeof vi.fn<[number], void>>): number[] {
  return fn.mock.calls.map((c) => c[0]);
}

afterEach(() => {
  vi.useRealTimers();
});

describe('FrameNavigator — 이동 버튼 4종', () => {
  it('처음_이전_다음_마지막_버튼이_모두_존재한다', () => {
    setup();
    expect(screen.getByRole('button', { name: '처음 프레임' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이전 프레임' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다음 프레임' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '마지막 프레임' })).toBeInTheDocument();
  });

  it('각_버튼이_단일_콜백_onRequestGoTo_로_수렴한다', () => {
    const { onRequestGoTo } = setup({ frameIndex: 4, frameCount: 10 });
    fireEvent.click(screen.getByRole('button', { name: '처음 프레임' }));
    fireEvent.click(screen.getByRole('button', { name: '이전 프레임' }));
    fireEvent.click(screen.getByRole('button', { name: '다음 프레임' }));
    fireEvent.click(screen.getByRole('button', { name: '마지막 프레임' }));
    expect(calls(onRequestGoTo)).toEqual([0, 3, 5, 9]);
  });

  it('첫_프레임에서는_처음_이전이_비활성이다', () => {
    setup({ frameIndex: 0 });
    expect(screen.getByRole('button', { name: '처음 프레임' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '이전 프레임' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '다음 프레임' })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: '마지막 프레임' })).not.toBeDisabled();
  });

  it('마지막_프레임에서는_다음_마지막이_비활성이다', () => {
    setup({ frameIndex: 9, frameCount: 10 });
    expect(screen.getByRole('button', { name: '다음 프레임' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '마지막 프레임' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '이전 프레임' })).not.toBeDisabled();
  });

  it('disabled_면_모든_진입점이_비활성이다_작업과_교차하면_반영_대상이_결정되지_않는다', () => {
    setup({ disabled: true, showSlider: true });
    expect(screen.getByRole('button', { name: '처음 프레임' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '다음 프레임' })).toBeDisabled();
    expect(screen.getByTestId('frame-number-input')).toBeDisabled();
    expect(screen.getByTestId('frame-position-slider')).toBeDisabled();
  });
});

describe('FrameNavigator — 번호 직접 입력', () => {
  it('현재_번호는_1부터_표시되고_전체_개수를_함께_보여준다', () => {
    setup({ frameIndex: 2, frameCount: 10 });
    expect((screen.getByTestId('frame-number-input') as HTMLInputElement).value).toBe('3');
    expect(screen.getByTestId('frame-total-count').textContent).toContain('10');
  });

  it('번호_입력_후_Enter_로_이동한다_같은_단일_콜백을_부른다', () => {
    const { onRequestGoTo } = setup({ frameIndex: 2, frameCount: 10 });
    const input = screen.getByTestId('frame-number-input');
    fireEvent.change(input, { target: { value: '7' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    // 화면 표기는 1부터, 콜백은 0부터.
    expect(onRequestGoTo).toHaveBeenCalledWith(6);
  });

  it('범위_밖이면_이동하지_않고_현재_번호로_되돌린다', () => {
    const { onRequestGoTo } = setup({ frameIndex: 2, frameCount: 10 });
    const input = screen.getByTestId('frame-number-input') as HTMLInputElement;

    fireEvent.change(input, { target: { value: '0' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onRequestGoTo).not.toHaveBeenCalled();
    expect(input.value).toBe('3');

    fireEvent.change(input, { target: { value: '11' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onRequestGoTo).not.toHaveBeenCalled();
    expect(input.value).toBe('3');
  });

  it('빈_값이면_이동하지_않고_현재_번호로_되돌린다', () => {
    const { onRequestGoTo } = setup({ frameIndex: 2, frameCount: 10 });
    const input = screen.getByTestId('frame-number-input') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onRequestGoTo).not.toHaveBeenCalled();
    expect(input.value).toBe('3');
  });

  it('숫자가_아니면_이동하지_않는다', () => {
    const { onRequestGoTo } = setup({ frameIndex: 2, frameCount: 10 });
    const input = screen.getByTestId('frame-number-input') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '3a' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onRequestGoTo).not.toHaveBeenCalled();
    expect(input.value).toBe('3');
  });

  it('포커스를_잃으면_확정하지_않고_되돌린다', () => {
    const { onRequestGoTo } = setup({ frameIndex: 2, frameCount: 10 });
    const input = screen.getByTestId('frame-number-input') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '8' } });
    fireEvent.blur(input);
    expect(onRequestGoTo).not.toHaveBeenCalled();
    expect(input.value).toBe('3');
  });
});

describe('FrameNavigator — 위치 슬라이더(throttle + release flush)', () => {
  it('showSlider_가_거짓이면_슬라이더를_렌더하지_않는다', () => {
    setup({ showSlider: false });
    expect(screen.queryByTestId('frame-position-slider')).toBeNull();
  });

  it('★끄는_동안_요청을_솎아내고_놓는_순간_마지막_위치를_반드시_반영한다', () => {
    // 솎아내기만 하고 마무리하지 않으면 마지막 위치가 누락된다(사양이 명시한 결함).
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-07T00:00:00Z'));
    const { onRequestGoTo } = setup({ frameIndex: 0, frameCount: 100, showSlider: true });
    const slider = screen.getByTestId('frame-position-slider');

    fireEvent.change(slider, { target: { value: '10' } }); // 첫 요청 — 즉시 발송
    vi.setSystemTime(new Date('2026-08-07T00:00:00.020Z'));
    fireEvent.change(slider, { target: { value: '20' } }); // 창 안 — 솎아냄
    vi.setSystemTime(new Date('2026-08-07T00:00:00.040Z'));
    fireEvent.change(slider, { target: { value: '30' } }); // 창 안 — 솎아냄

    expect(calls(onRequestGoTo)).toEqual([10]);

    fireEvent.mouseUp(slider); // 놓는 순간 대기분 반영
    expect(calls(onRequestGoTo)).toEqual([10, 30]);
  });

  it('솎아내기_간격을_넘기면_드래그_중에도_다시_보낸다', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-07T00:00:00Z'));
    const { onRequestGoTo } = setup({ frameIndex: 0, frameCount: 100, showSlider: true });
    const slider = screen.getByTestId('frame-position-slider');

    fireEvent.change(slider, { target: { value: '10' } });
    vi.setSystemTime(new Date('2026-08-07T00:00:00.500Z'));
    fireEvent.change(slider, { target: { value: '40' } });

    expect(calls(onRequestGoTo)).toEqual([10, 40]);
  });

  it('대기분이_없으면_놓아도_중복_요청하지_않는다', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-07T00:00:00Z'));
    const { onRequestGoTo } = setup({ frameIndex: 0, frameCount: 100, showSlider: true });
    const slider = screen.getByTestId('frame-position-slider');

    fireEvent.change(slider, { target: { value: '10' } });
    fireEvent.mouseUp(slider);

    expect(calls(onRequestGoTo)).toEqual([10]);
  });

  it('끄는_동안_썸은_로컬_표시값을_따르고_놓으면_실제_위치로_돌아온다', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-07T00:00:00Z'));
    setup({ frameIndex: 0, frameCount: 100, showSlider: true });
    const slider = screen.getByTestId('frame-position-slider') as HTMLInputElement;

    fireEvent.change(slider, { target: { value: '10' } });
    vi.setSystemTime(new Date('2026-08-07T00:00:00.020Z'));
    fireEvent.change(slider, { target: { value: '55' } });
    // 부모가 아직 frameIndex 를 갱신하지 않아도 썸은 끌린 자리에 있다.
    expect(slider.value).toBe('55');

    fireEvent.mouseUp(slider);
    // 놓으면 실제 프레임 위치(부모 상태)를 따른다.
    expect(slider.value).toBe('0');
  });
});

describe('FrameNavigator — 접근성', () => {
  it('컨테이너는_role_group_이고_번호_입력에_이름이_있다', () => {
    setup();
    expect(screen.getByRole('group', { name: '프레임 이동' })).toBeInTheDocument();
    expect(screen.getByLabelText('프레임 번호')).toBeInTheDocument();
  });

  it('이동_버튼은_KRDS_최소_터치_타깃_44px_를_보장한다', () => {
    setup();
    for (const name of ['처음 프레임', '이전 프레임', '다음 프레임', '마지막 프레임']) {
      const btn = screen.getByRole('button', { name });
      expect(btn.className).toMatch(/\bh-11\b/);
      expect(btn.className).toMatch(/\bw-11\b/);
    }
  });
});
