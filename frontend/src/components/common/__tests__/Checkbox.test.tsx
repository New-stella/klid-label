import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Checkbox } from '../Checkbox';

describe('Checkbox', () => {
  it('Checkbox_label_클릭으로_토글', async () => {
    const user = userEvent.setup();
    function Wrapper() {
      return <Checkbox label="동의합니다" />;
    }
    render(<Wrapper />);
    const cb = screen.getByLabelText('동의합니다') as HTMLInputElement;
    expect(cb.checked).toBe(false);
    await user.click(screen.getByText('동의합니다'));
    expect(cb.checked).toBe(true);
  });

  it('Checkbox_클릭영역_44px', () => {
    // given/when: 라벨이 있는 체크박스 렌더
    render(<Checkbox label="동의합니다" />);
    // then: 클릭 가능 영역(input+label 래퍼)이 최소 44px(min-h-11)
    const wrapper = screen.getByRole('checkbox').closest('label');
    expect(wrapper?.className).toMatch(/min-h-11/);
  });

  it('Checkbox_label_없을때_44px_폭_가드', () => {
    // given/when: 라벨 없는 체크박스 렌더
    render(<Checkbox aria-label="선택" />);
    // then: 시각 크기 유지하되 클릭영역 폭 44px(min-w-11) 가드
    const wrapper = screen.getByRole('checkbox').closest('label');
    expect(wrapper?.className).toMatch(/min-w-11/);
  });

  it('Checkbox_포커스링_KRDS', () => {
    // given/when: 체크박스 렌더
    render(<Checkbox label="동의합니다" />);
    // then: KRDS focus(3px ring + 2px offset + primary) 적용
    const cls = (screen.getByRole('checkbox') as HTMLInputElement).className;
    expect(cls).toMatch(/focus-visible:ring-\[3px\]/);
    expect(cls).toMatch(/focus-visible:ring-offset-2/);
    expect(cls).toMatch(/focus-visible:ring-primary/);
  });
});
