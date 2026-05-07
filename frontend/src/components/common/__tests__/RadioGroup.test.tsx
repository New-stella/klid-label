import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { RadioGroup } from '../RadioGroup';

describe('RadioGroup', () => {
  it('RadioGroup_role_radiogroup_제공', () => {
    render(
      <RadioGroup
        name="ch"
        label="채널"
        options={[
          { value: 'A', label: '관제' },
          { value: 'B', label: '포털' },
        ]}
      />,
    );
    expect(screen.getByRole('radiogroup', { name: '채널' })).toBeInTheDocument();
  });

  it('RadioGroup_옵션_클릭_시_onChange_호출', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <RadioGroup
        name="ch"
        label="채널"
        onChange={onChange}
        options={[
          { value: 'A', label: '관제' },
          { value: 'B', label: '포털' },
        ]}
      />,
    );
    await user.click(screen.getByLabelText('포털'));
    expect(onChange).toHaveBeenCalledWith('B');
  });
});
