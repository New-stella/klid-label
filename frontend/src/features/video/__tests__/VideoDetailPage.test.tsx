import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { VideoDetailPage } from '@/pages/VideoDetailPage';
import { renderWithProviders } from '@/test/renderWithProviders';

describe('VideoDetailPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('영상_상세_프레임_미리보기_6장_노출', async () => {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        cctvName: '강남대로 CCTV',
        vmsClipId: 'VMS-42',
        eventName: '낙상',
        eventTypeCd: 'FALL',
        localGov: '강남구',
        frameCount: 900,
        status: 'COMPLETED',
        capturedAt: '2026-05-01T12:00:00Z',
        duration: 30,
        fileSizeMb: 10,
        resolution: '1920x1080',
        framePreviews: Array.from({ length: 6 }, (_, i) => ({
          frameNo: i * 150 + 1,
          thumbnailUrl: `/t/${i}.jpg`,
        })),
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/video/:id" element={<VideoDetailPage />} />
      </Routes>,
      { initialEntries: ['/video/42'] },
    );

    // 영상 데이터 로드 대기
    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });

    // 프레임 미리보기 탭 활성화 (Tabs 컴포넌트는 role="tab" 사용)
    await user.click(screen.getByRole('tab', { name: /프레임 미리보기/ }));

    // 6장 프레임 버튼이 렌더된다 (aria-label="프레임 N 상세 보기")
    await waitFor(() => {
      const frameButtons = screen.getAllByRole('button', { name: /^프레임 \d+ 상세 보기$/ });
      expect(frameButtons).toHaveLength(6);
    });
  });

  it('잘못된_id는_ErrorState_노출', () => {
    renderWithProviders(
      <Routes>
        <Route path="/video/:id" element={<VideoDetailPage />} />
      </Routes>,
      { initialEntries: ['/video/invalid'] },
    );

    expect(screen.getByText('잘못된 영상 ID')).toBeInTheDocument();
  });
});
