import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { Popover } from '../Popover';

describe('Popover', () => {
  it('Popover_트리거_44px', () => {
    // KRDS 터치 타깃 44px — 트리거 버튼에 min-h-11 min-w-11 히트영역
    render(<Popover trigger={<span>메뉴</span>}>내용</Popover>);
    const btn = screen.getByRole('button');
    expect(btn.className).toMatch(/min-h-11/);
    expect(btn.className).toMatch(/min-w-11/);
  });
});
