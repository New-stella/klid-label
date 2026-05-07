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
});
