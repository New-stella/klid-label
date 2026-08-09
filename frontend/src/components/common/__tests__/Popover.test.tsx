import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Popover } from '../Popover';

describe('Popover', () => {
  it('Popover_트리거_44px', () => {
    // KRDS 터치 타깃 44px — 트리거 버튼에 min-h-11 min-w-11 히트영역
    render(<Popover trigger={<span>메뉴</span>}>내용</Popover>);
    const btn = screen.getByRole('button');
    expect(btn.className).toMatch(/min-h-11/);
    expect(btn.className).toMatch(/min-w-11/);
  });

  // ── UI-031 회귀 가드: controlled 모드 ─────────────────────────────────────
  // ⚠ 이 케이스만 fireEvent 를 쓴다 — 이 컴포넌트는 document 레벨 리스너를 걸어 두는데,
  //   userEvent 의 포인터 시퀀스로 **내부 state** 가 갱신되면 act(...) 경고가 stderr 에 쌓인다
  //   (동작·결과는 동일). controlled 케이스는 내부 state 갱신이 없어 userEvent 를 그대로 쓴다.
  it('Popover_비제어_모드는_트리거_클릭으로_열고_닫는다', () => {
    render(<Popover trigger={<span>메뉴</span>}>내용</Popover>);
    const btn = screen.getByRole('button');
    expect(btn).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(btn);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(btn).toHaveAttribute('aria-expanded', 'true');

    fireEvent.click(btn);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('Popover_open_을_주면_controlled_로_전환되어_내부_state_가_열지_못한다', async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    render(
      <Popover trigger={<span>메뉴</span>} open={false} onOpenChange={onOpenChange}>
        내용
      </Popover>,
    );
    await user.click(screen.getByRole('button'));

    // 열림 상태의 소유자는 호출부다 — 내부 state 로 스스로 열리지 않는다
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(onOpenChange).toHaveBeenCalledWith(true);
  });

  it('Popover_controlled_에서도_aria_expanded_는_실제_열림_상태를_반영한다', () => {
    const { rerender } = render(
      <Popover trigger={<span>메뉴</span>} open onOpenChange={() => {}}>
        내용
      </Popover>,
    );
    expect(screen.getByRole('button')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    rerender(
      <Popover trigger={<span>메뉴</span>} open={false} onOpenChange={() => {}}>
        내용
      </Popover>,
    );
    expect(screen.getByRole('button')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('Popover_controlled_에서_ESC_는_닫기를_통지한다', async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    render(
      <Popover trigger={<span>메뉴</span>} open onOpenChange={onOpenChange}>
        내용
      </Popover>,
    );
    await user.keyboard('{Escape}');
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});
