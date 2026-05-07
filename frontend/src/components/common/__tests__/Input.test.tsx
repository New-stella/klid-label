import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useForm } from 'react-hook-form';

import { Input } from '../Input';

describe('Input', () => {
  it('Input_에러_메시지_aria_describedby_연결', () => {
    render(
      <Input id="email" label="이메일" error="필수 항목입니다" />,
    );
    const input = screen.getByLabelText('이메일');
    const describedBy = input.getAttribute('aria-describedby');
    expect(describedBy).toBeTruthy();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    const errorEl = document.getElementById(describedBy!);
    expect(errorEl).toHaveTextContent('필수 항목입니다');
  });

  it('Input_react_hook_form_register와_연동', async () => {
    const user = userEvent.setup();
    function Form() {
      const { register, watch } = useForm<{ name: string }>({ defaultValues: { name: '' } });
      const name = watch('name');
      return (
        <div>
          <Input id="name" label="이름" {...register('name')} />
          <span data-testid="value">{name}</span>
        </div>
      );
    }
    render(<Form />);
    await user.type(screen.getByLabelText('이름'), '홍길동');
    expect(screen.getByTestId('value')).toHaveTextContent('홍길동');
  });
});
