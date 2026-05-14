import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';

import { LabelHeader } from '@/features/label/components/LabelHeader';
import { renderWithProviders } from '@/test/renderWithProviders';

function setup(overrides: Partial<Parameters<typeof LabelHeader>[0]> = {}) {
  const props = {
    currentFrame: 0,
    totalFrames: 5,
    objectCount: 3,
    dirty: false,
    videoId: 42,
    showHistory: true,
    onSave: vi.fn(),
    ...overrides,
  } as Parameters<typeof LabelHeader>[0];
  return { props, ...renderWithProviders(<LabelHeader {...props} />) };
}

describe('LabelHeader 히스토리 버튼', () => {
  it('onHistoryClick_미지정시_Link_로_렌더_(fallback)', () => {
    setup();
    // Link 는 anchor 로 렌더됨 — href 가 /history/{videoId}
    const link = screen.getByRole('link', { name: /히스토리/ });
    expect(link).toHaveAttribute('href', '/history/42');
    // 토글 버튼은 없음
    expect(screen.queryByTestId('history-toggle')).toBeNull();
  });

  it('onHistoryClick_지정시_토글_버튼_렌더_+_클릭_콜백_호출', () => {
    const onHistoryClick = vi.fn();
    setup({ onHistoryClick });
    const toggle = screen.getByTestId('history-toggle');
    expect(toggle).toBeInTheDocument();
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(toggle);
    expect(onHistoryClick).toHaveBeenCalledTimes(1);
    // Link fallback 미렌더
    expect(screen.queryByRole('link', { name: /히스토리/ })).toBeNull();
  });

  it('historyOpen_true_일_때_aria_expanded_true', () => {
    setup({ onHistoryClick: vi.fn(), historyOpen: true });
    expect(screen.getByTestId('history-toggle')).toHaveAttribute('aria-expanded', 'true');
  });

  it('showHistory_false_또는_videoId_없으면_미노출', () => {
    const { unmount } = setup({ showHistory: false });
    expect(screen.queryByRole('link', { name: /히스토리/ })).toBeNull();
    expect(screen.queryByTestId('history-toggle')).toBeNull();
    unmount();

    setup({ showHistory: true, videoId: undefined });
    expect(screen.queryByRole('link', { name: /히스토리/ })).toBeNull();
    expect(screen.queryByTestId('history-toggle')).toBeNull();
  });
});
