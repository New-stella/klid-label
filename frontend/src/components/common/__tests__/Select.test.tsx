import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Select } from '../Select';

describe('Select', () => {
  it('Select_options_렌더_+_변경_시_onChange', async () => {
    const user = userEvent.setup();
    let value = '';
    const handle = (e: React.ChangeEvent<HTMLSelectElement>) => {
      value = e.target.value;
    };
    render(
      <Select
        label="채널"
        options={[
          { value: 'A', label: '관제' },
          { value: 'B', label: '포털' },
        ]}
        onChange={handle}
      />,
    );
    const sel = screen.getByLabelText('채널');
    await user.selectOptions(sel, 'B');
    expect(value).toBe('B');
  });
});
