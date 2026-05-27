import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { MarkingToolbar } from '../MarkingToolbar';

describe('MarkingToolbar intervalFrames', () => {
  const defaultProps = {
    mode: 'AUTO' as const,
    eventName: '화재',
    intervalFrames: 30,
    onModeChange: vi.fn(),
    onEventNameChange: vi.fn(),
    onIntervalFramesChange: vi.fn(),
    onSubmit: vi.fn(),
    onClear: vi.fn(),
    markCount: 0,
  };

  it('자동모드_간격_라벨에_프레임_표시', () => {
    // given
    render(<MarkingToolbar {...defaultProps} />);

    // when & then: "간격(프레임)" 텍스트가 존재해야 한다
    expect(screen.getByText(/간격\(프레임\)/)).toBeInTheDocument();
  });

  it('자동모드_간격_입력_max가_3600', () => {
    // given
    render(<MarkingToolbar {...defaultProps} />);

    // when
    const input = screen.getByRole('spinbutton');

    // then: max 속성이 3600 이어야 한다
    expect(input).toHaveAttribute('max', '3600');
  });
});
