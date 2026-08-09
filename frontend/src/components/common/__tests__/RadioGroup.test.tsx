import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Field, FieldLabel } from '../Field';
import { RadioGroup } from '../RadioGroup';

const OPTIONS = [
  { value: 'A', label: '관제' },
  { value: 'B', label: '포털' },
];

describe('RadioGroup', () => {
  it('RadioGroup_role_radiogroup_제공', () => {
    render(
      <Field>
        <FieldLabel>채널</FieldLabel>
        <RadioGroup name="ch" options={OPTIONS} />
      </Field>,
    );
    // 그룹 이름은 FieldLabel 이 aria-labelledby 로 잇는다(조합형 전환 후에도 유지).
    expect(screen.getByRole('radiogroup', { name: '채널' })).toBeInTheDocument();
  });

  it('RadioGroup_옵션_클릭_시_onChange_호출', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <Field>
        <FieldLabel>채널</FieldLabel>
        <RadioGroup name="ch" onChange={onChange} options={OPTIONS} />
      </Field>,
    );
    await user.click(screen.getByLabelText('포털'));
    expect(onChange).toHaveBeenCalledWith('B');
  });

  it('RadioGroup_Field_밖에서는_aria_label_로_이름을_받는다', () => {
    render(<RadioGroup name="ch" aria-label="채널" options={OPTIONS} />);
    expect(screen.getByRole('radiogroup', { name: '채널' })).toBeInTheDocument();
  });
});
