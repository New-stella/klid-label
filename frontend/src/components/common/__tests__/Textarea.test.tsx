import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { Textarea } from '../Textarea';

describe('Textarea', () => {
  it('Textarea_min_44px', () => {
    // KRDS 터치 타깃 44px — textarea 최소 높이 min-h-11 명시
    render(<Textarea label="설명" />);
    const ta = screen.getByLabelText('설명');
    expect(ta.className).toMatch(/min-h-11/);
  });
});
