import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { MarkingTimeline, markAriaLabel } from '../MarkingTimeline';
import type { MarkItem } from '../../types';

const marks: MarkItem[] = [
  { frameIndex: 30, timestamp: '00:01' },
  { frameIndex: 300, timestamp: '00:10' },
];

describe('MarkingTimeline 접근성 (UI-044)', () => {
  // 마크는 폭 4px 막대라 시각 텍스트가 없다. 구 구현은 `title`(툴팁)만 있어
  // **버튼에 접근성 이름이 아예 없었고** 스크린리더로는 어느 프레임인지 알 수 없었다.
  it('각_마크_버튼은_프레임번호와_시각을_aria_label_로_노출한다', () => {
    render(
      <MarkingTimeline
        marks={marks}
        durationSec={20}
        fps={30}
        selectedIndex={null}
        onSelect={() => {}}
      />,
    );
    expect(screen.getByRole('button', { name: 'F30·00:01' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'F300·00:10' })).toBeInTheDocument();
  });

  it('시각이_없으면_프레임번호만_노출한다', () => {
    expect(markAriaLabel({ frameIndex: 42, timestamp: null })).toBe('F42');
    expect(markAriaLabel({ frameIndex: 42, timestamp: '00:01' })).toBe('F42·00:01');
  });

  it('마크_클릭_시_해당_인덱스를_선택한다', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(
      <MarkingTimeline
        marks={marks}
        durationSec={20}
        fps={30}
        selectedIndex={null}
        onSelect={onSelect}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'F300·00:10' }));
    expect(onSelect).toHaveBeenCalledWith(1);
  });

  it('총_프레임수가_0_이하면_렌더하지_않는다', () => {
    const { container } = render(
      <MarkingTimeline
        marks={marks}
        durationSec={0}
        fps={30}
        selectedIndex={null}
        onSelect={() => {}}
      />,
    );
    expect(container.firstChild).toBeNull();
  });
});
