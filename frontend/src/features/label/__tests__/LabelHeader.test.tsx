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
  it('onHistoryClick_미지정시_히스토리_버튼_미노출_(회귀가드)', () => {
    // 별도 페이지 /history/:videoId (HistoryPage) 는 2026-08-03 제거됐다.
    // 구 fallback <Link to="/history/{videoId}"> 는 404 로 가는 죽은 링크이므로
    // 콜백이 없으면 히스토리 버튼 자체를 렌더하지 않는다.
    setup();
    expect(screen.queryByRole('link', { name: /히스토리/ })).toBeNull();
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
