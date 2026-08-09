import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

import { DatePicker } from '../DatePicker';
import { Field, FieldLabel } from '../Field';

describe('DatePicker', () => {
  it('DatePicker_한국어_월_표시', () => {
    render(
      <Field>
        <FieldLabel>발생일</FieldLabel>
        <DatePicker value="2026-05-07" onChange={() => {}} />
      </Field>,
    );
    expect(screen.getByTestId('datepicker-display')).toHaveTextContent('2026년 5월 7일');
  });

  it('DatePicker_변경_시_onChange_호출', () => {
    const onChange = vi.fn();
    render(
      <Field>
        <FieldLabel>발생일</FieldLabel>
        <DatePicker value="2026-05-07" onChange={onChange} />
      </Field>,
    );
    const input = screen.getByLabelText('발생일');
    fireEvent.change(input, { target: { value: '2026-05-10' } });
    expect(onChange).toHaveBeenCalledWith('2026-05-10');
  });
});
