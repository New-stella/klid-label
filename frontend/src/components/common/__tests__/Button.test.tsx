import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Button } from '../Button';

describe('Button', () => {
  it('Button_loading_상태에서_disabled_+_스피너_노출', () => {
    render(<Button loading>저장</Button>);
    const btn = screen.getByRole('button');
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('Button_5종_variant_적절한_색상_클래스', () => {
    const { rerender } = render(<Button variant="primary">P</Button>);
    expect(screen.getByRole('button').className).toMatch(/bg-primary/);

    rerender(<Button variant="secondary">S</Button>);
    // secondary는 white 배경 + gray border 스타일 (mock tone)
    expect(screen.getByRole('button').className).toMatch(/bg-white|border-gray/);

    rerender(<Button variant="outline">O</Button>);
    expect(screen.getByRole('button').className).toMatch(/border/);

    rerender(<Button variant="danger">D</Button>);
    expect(screen.getByRole('button').className).toMatch(/bg-danger/);

    rerender(<Button variant="ghost">G</Button>);
    expect(screen.getByRole('button').className).toMatch(/bg-transparent|hover:bg/);
  });

  it('Button_클릭_시_onClick_호출', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(<Button onClick={onClick}>확인</Button>);
    await user.click(screen.getByRole('button'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('Button_disabled_일_때_클릭_안됨', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(
      <Button onClick={onClick} disabled>
        확인
      </Button>,
    );
    await user.click(screen.getByRole('button'));
    expect(onClick).not.toHaveBeenCalled();
  });

  it('Button_fullWidth_w_full_클래스', () => {
    render(<Button fullWidth>전체</Button>);
    expect(screen.getByRole('button').className).toMatch(/w-full/);
  });
});
