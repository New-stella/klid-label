import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Radio } from '../Radio';

describe('Radio', () => {
  it('Radio_label_클릭으로_선택', async () => {
    // given: 라벨이 있는 라디오
    const user = userEvent.setup();
    render(<Radio name="ch" value="A" label="관제" />);
    const radio = screen.getByLabelText('관제') as HTMLInputElement;
    expect(radio.checked).toBe(false);
    // when: 라벨 텍스트 클릭
    await user.click(screen.getByText('관제'));
    // then: 선택됨 (기존 토글 동작 유지)
    expect(radio.checked).toBe(true);
  });

  it('Radio_포커스링_KRDS', () => {
    // given/when: 라디오 렌더
    render(<Radio name="ch" value="A" label="관제" />);
    // then: KRDS focus(3px ring + 2px offset + primary) 적용
    const cls = (screen.getByRole('radio') as HTMLInputElement).className;
    expect(cls).toMatch(/focus-visible:ring-\[3px\]/);
    expect(cls).toMatch(/focus-visible:ring-offset-2/);
    expect(cls).toMatch(/focus-visible:ring-primary/);
  });

  it('Radio_클릭영역_44px', () => {
    // given/when: 라벨이 있는 라디오
    render(<Radio name="ch" value="A" label="관제" />);
    // then: 클릭 가능 영역이 최소 44px(min-h-11)
    const wrapper = screen.getByRole('radio').closest('label');
    expect(wrapper?.className).toMatch(/min-h-11/);
  });

  it('Radio_label_없을때_44px_폭_가드', () => {
    // given/when: 라벨 없는 라디오
    render(<Radio name="ch" value="A" aria-label="선택" />);
    // then: 시각 크기 유지하되 클릭영역 폭 44px(min-w-11) 가드
    const wrapper = screen.getByRole('radio').closest('label');
    expect(wrapper?.className).toMatch(/min-w-11/);
  });
});
