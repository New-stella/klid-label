import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ReviewHeader } from '../components/ReviewHeader';

describe('ReviewHeader', () => {
  const baseProps = {
    videoId: 42,
    cctvName: 'CCTV-1',
    workerName: '홍길동',
    submittedAt: '2026-05-07T01:30:00Z',
    currentFrame: 3,
    totalFrames: 10,
    status: 'REVIEWING' as const,
    onClose: () => {},
  };

  it('검수페이지_로드시_헤더에_영상명과_작업자_표시', () => {
    render(<ReviewHeader {...baseProps} />);

    expect(screen.getByTestId('review-header-cctv-name')).toHaveTextContent('CCTV-1');
    expect(screen.getByTestId('review-header-worker-name')).toHaveTextContent('홍길동');
    expect(screen.getByTestId('review-header-frame-counter')).toHaveTextContent(
      'Frame 3/10',
    );
  });

  it('닫기_버튼_클릭시_onClose_콜백_호출', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();

    render(<ReviewHeader {...baseProps} onClose={onClose} />);

    const closeBtn = screen.getByRole('button', { name: '검수 페이지 닫기' });
    await user.click(closeBtn);

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('상태_배지_렌더링', () => {
    render(<ReviewHeader {...baseProps} status="COMPLETED" />);
    // StatusBadge data-status attribute로 검증
    const badge = document.querySelector('[data-status="COMPLETED"]');
    expect(badge).not.toBeNull();
  });
});
