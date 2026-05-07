import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';

import { Toast } from '../Toast';

describe('Toast', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('Toast_5초_후_자동_dismiss', () => {
    const onDismiss = vi.fn();
    render(<Toast id="t-1" variant="success" message="저장되었습니다" onDismiss={onDismiss} />);

    expect(screen.getByText('저장되었습니다')).toBeInTheDocument();
    expect(onDismiss).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(4999);
    });
    expect(onDismiss).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(onDismiss).toHaveBeenCalledWith('t-1');
  });

  it('Toast_durationMs_지정시_해당_시점에_dismiss', () => {
    const onDismiss = vi.fn();
    render(
      <Toast
        id="t-2"
        variant="error"
        message="실패"
        durationMs={1000}
        onDismiss={onDismiss}
      />,
    );

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(onDismiss).toHaveBeenCalledWith('t-2');
  });
});
