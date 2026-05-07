import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import type { PortalUpload } from '../../types';
import { MyUploadList } from '../MyUploadList';

const sample: PortalUpload[] = [
  {
    srcSn: 1,
    displayName: 'video1.mp4',
    uploadedAt: '2026-05-07T10:00:00Z',
    status: 'COMPLETED',
    fileSize: 1024 * 1024,
  },
  {
    srcSn: 2,
    displayName: 'video2.mp4',
    uploadedAt: '2026-05-07T11:00:00Z',
    status: 'AUTOLABEL_DONE',
    fileSize: 2048,
  },
];

describe('MyUploadList', () => {
  it('업로드_목록_표시', () => {
    render(
      <MemoryRouter>
        <MyUploadList uploads={sample} />
      </MemoryRouter>,
    );

    expect(screen.getByText('video1.mp4')).toBeInTheDocument();
    expect(screen.getByText('video2.mp4')).toBeInTheDocument();
  });

  it('빈_목록_시_안내_표시', () => {
    render(
      <MemoryRouter>
        <MyUploadList uploads={[]} />
      </MemoryRouter>,
    );
    expect(screen.getByText(/업로드된 영상이 없습니다/)).toBeInTheDocument();
  });

  it('다운로드_UI_컴포넌트_미존재_V1_5_포털_자체_책임', () => {
    const { container } = render(
      <MemoryRouter>
        <MyUploadList uploads={sample} />
      </MemoryRouter>,
    );
    // V1.5: 다운로드 카드/배지/버튼 mock에 없어야 함
    expect(container.textContent).not.toMatch(/다운로드/);
    expect(container.querySelector('[aria-label*="다운로드"]')).toBeNull();
    expect(container.querySelector('[data-testid*="download"]')).toBeNull();
  });
});
