import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

import { DateRangePicker } from '../DateRangePicker';

/**
 * 기간 입력 사양 컴포넌트(UI-029)의 계약 가드.
 *
 * 이 컴포넌트는 오래 소비처가 없어(사장 상태) 계약이 한 번도 실행으로 확인된 적이 없었다.
 * 목록 화면 기간 필터가 여기에 배선되면서 아래 계약이 화면 동작을 직접 좌우한다.
 */
describe('DateRangePicker', () => {
  it('시작일을_바꾸면_기존_종료일을_유지한_채_한_쌍으로_올린다', () => {
    // given
    const onChange = vi.fn();
    render(<DateRangePicker value={{ to: '2026-05-31' }} onChange={onChange} />);

    // when
    fireEvent.change(screen.getByLabelText('시작일'), { target: { value: '2026-05-01' } });

    // then: 반대편 값을 흘리면 한쪽을 고칠 때마다 다른 쪽이 지워진다
    expect(onChange).toHaveBeenCalledWith({ from: '2026-05-01', to: '2026-05-31' });
  });

  it('종료일을_바꾸면_기존_시작일을_유지한_채_한_쌍으로_올린다', () => {
    // given
    const onChange = vi.fn();
    render(<DateRangePicker value={{ from: '2026-05-01' }} onChange={onChange} />);

    // when
    fireEvent.change(screen.getByLabelText('종료일'), { target: { value: '2026-05-31' } });

    // then
    expect(onChange).toHaveBeenCalledWith({ from: '2026-05-01', to: '2026-05-31' });
  });

  it('두_입력은_서로의_상한_하한이_된다', () => {
    // given / when
    render(<DateRangePicker value={{ from: '2026-05-01', to: '2026-05-31' }} />);

    // then
    expect(screen.getByLabelText('시작일')).toHaveAttribute('max', '2026-05-31');
    expect(screen.getByLabelText('종료일')).toHaveAttribute('min', '2026-05-01');
  });

  it('반대편이_비어있으면_제약_속성을_붙이지_않는다', () => {
    // given / when: 값이 아예 없는 초기 상태
    render(<DateRangePicker />);

    // then: 빈 문자열 제약은 브라우저가 유효 제약으로 해석할 여지가 있다
    expect(screen.getByLabelText('시작일')).not.toHaveAttribute('max');
    expect(screen.getByLabelText('종료일')).not.toHaveAttribute('min');
  });

  it('그룹_라벨을_주면_두_입력이_이름있는_그룹으로_묶인다', () => {
    // given / when
    render(<DateRangePicker label="촬영 기간" value={{ from: '2026-05-01' }} />);

    // then
    const group = screen.getByRole('group', { name: '촬영 기간' });
    expect(group).toContainElement(screen.getByLabelText('시작일'));
    expect(group).toContainElement(screen.getByLabelText('종료일'));
  });

  it('한국어_병기는_기본으로_표시하고_끄면_렌더하지_않는다', () => {
    // given / when: 기본값
    const { unmount } = render(<DateRangePicker value={{ from: '2026-05-07' }} />);

    // then
    expect(screen.getAllByTestId('datepicker-display')[0]).toHaveTextContent('2026년 5월 7일');

    // when: 끈 경우 — 값·라벨은 그대로고 병기 표시만 사라진다
    unmount();
    render(<DateRangePicker value={{ from: '2026-05-07' }} showLocalizedDisplay={false} />);

    // then
    expect(screen.queryByTestId('datepicker-display')).not.toBeInTheDocument();
    expect(screen.getByLabelText('시작일')).toHaveValue('2026-05-07');
  });
});
