import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

import { VideoActions } from '../components/VideoActions';
import type { Video } from '../types';

const baseVideo: Video = {
  id: 1,
  cctvName: 'CCTV-1',
  vmsClipId: 'VMS-1',
  eventName: '화재',
  eventTypeCd: 'FIRE',
  localGov: '강남구',
  frameCount: 900,
  status: 'COMPLETED',
  capturedAt: '2026-05-01T12:00:00Z',
};

function renderWithRouter(ui: React.ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

describe('VideoActions', () => {
  it('REVIEWER만_배정_버튼_노출', () => {
    const { unmount } = renderWithRouter(
      <VideoActions video={baseVideo} canAssign canRequestBg={false} />,
    );
    expect(screen.getByRole('button', { name: '배정' })).toBeInTheDocument();
    unmount();

    renderWithRouter(
      <VideoActions video={baseVideo} canAssign={false} canRequestBg={false} />,
    );
    expect(screen.queryByRole('button', { name: '배정' })).not.toBeInTheDocument();
  });

  it('REVIEWER만_배경영상_요청_버튼_노출', () => {
    const { unmount } = renderWithRouter(
      <VideoActions video={baseVideo} canAssign={false} canRequestBg />,
    );
    expect(screen.getByRole('button', { name: /배경영상 요청/ })).toBeInTheDocument();
    unmount();

    renderWithRouter(
      <VideoActions video={baseVideo} canAssign={false} canRequestBg={false} />,
    );
    expect(screen.queryByRole('button', { name: /배경영상 요청/ })).not.toBeInTheDocument();
  });

  it('상세_버튼은_권한_무관_노출', () => {
    renderWithRouter(
      <VideoActions video={baseVideo} canAssign={false} canRequestBg={false} />,
    );
    expect(screen.getByRole('button', { name: '상세' })).toBeInTheDocument();
  });

  it('onDetail_콜백_제공_시_콜백_호출', async () => {
    const user = userEvent.setup();
    const onDetail = vi.fn();
    renderWithRouter(
      <VideoActions
        video={baseVideo}
        canAssign={false}
        canRequestBg={false}
        onDetail={onDetail}
      />,
    );
    await user.click(screen.getByRole('button', { name: '상세' }));
    expect(onDetail).toHaveBeenCalledWith(baseVideo);
  });
});
