import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Tabs } from '../Tabs';

describe('Tabs', () => {
  const items = [
    { value: 'a', label: 'A' },
    { value: 'b', label: 'B' },
    { value: 'c', label: 'C' },
  ];

  it('Tabs_active_tab_aria_selected_true', () => {
    render(<Tabs items={items} value="b" onChange={() => {}} />);
    const b = screen.getByRole('tab', { name: 'B' });
    expect(b).toHaveAttribute('aria-selected', 'true');
    const a = screen.getByRole('tab', { name: 'A' });
    expect(a).toHaveAttribute('aria-selected', 'false');
  });

  it('Tabs_방향키_ArrowRight_다음_탭_이동', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<Tabs items={items} value="a" onChange={onChange} />);
    const a = screen.getByRole('tab', { name: 'A' });
    a.focus();
    await user.keyboard('{ArrowRight}');
    expect(onChange).toHaveBeenCalledWith('b');
  });

  // ── SCREEN-009 확정 시안 정합: 세로 레일 선택 탭 굵기 ──────────────────────
  // 기준: `design-main.css` — `.tab-rail label[for=...]` 선택 상태가 `font-weight: 600`.
  // ⚠ 세로/가로는 **강조 축이 다르다** — 세로는 좌측 보더 + 틴트 배경 + 굵기, 가로는 밑줄이다.
  //   가로까지 600 으로 올리면 강조가 이중이 된다. "일관성"을 이유로 통일하지 말 것.
  describe('★선택 탭 굵기(@design SCREEN-009)', () => {
    const weightOf = (name: string): string =>
      (screen.getByRole('tab', { name }).className.match(/font-\w+/g) ?? []).join(' ');

    it('★세로_레일의_선택_탭은_600이다', () => {
      render(<Tabs orientation="vertical" items={items} value="b" onChange={() => {}} />);
      expect(weightOf('B')).toContain('font-semibold');
      // 비선택이 400 이라 굵기 차가 두 단이 되어 레일에서 선택 표식이 선다.
      expect(weightOf('A')).toContain('font-normal');
    });

    it('★가로_탭의_선택_굵기는_500_그대로다_세로와_통일_금지', () => {
      render(<Tabs items={items} value="b" onChange={() => {}} />);
      expect(weightOf('B')).toContain('font-medium');
      expect(weightOf('B')).not.toContain('font-semibold');
    });
  });
});
