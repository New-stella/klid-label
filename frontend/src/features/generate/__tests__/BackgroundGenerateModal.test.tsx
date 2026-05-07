import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import type { Video } from '@/features/video/types';
import type { FrameGrid12Frame } from '@/features/deident/components/FrameGrid12';

import { BackgroundGenerateModal } from '../components/BackgroundGenerateModal';

const baseVideo: Video = {
  id: 10,
  cctvName: 'CCTV-A',
  vmsClipId: 'VMS-1',
  eventName: '화재',
  eventTypeCd: 'FIRE',
  localGov: '강남구',
  frameCount: 900,
  status: 'COMPLETED',
  capturedAt: '2026-05-01T12:00:00Z',
};

const baseFrames: FrameGrid12Frame[] = Array.from({ length: 4 }).map((_, i) => ({
  srcSn: i + 1,
  frameNo: i,
  originalUrl: `/o/${i}.jpg`,
  processedUrl: `/p/${i}.jpg`,
}));

describe('BackgroundGenerateModal', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('BackgroundGenerateModal_프레임만_선택시_요청_보내기_비활성', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <BackgroundGenerateModal
        open
        video={baseVideo}
        frames={baseFrames}
        onClose={() => {}}
      />,
    );

    const submit = await screen.findByTestId('bg-modal-submit');
    expect(submit).toBeDisabled();

    // 프레임만 선택
    const frame1 = screen.getByTestId('frame-pair-1').querySelector('input')!;
    await user.click(frame1);
    expect(submit).toBeDisabled();
  });

  it('BackgroundGenerateModal_유형까지_선택시_요청_보내기_활성', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <BackgroundGenerateModal
        open
        video={baseVideo}
        frames={baseFrames}
        onClose={() => {}}
      />,
    );

    const submit = await screen.findByTestId('bg-modal-submit');
    const frame1 = screen.getByTestId('frame-pair-1').querySelector('input')!;
    await user.click(frame1);

    // 산불 라디오
    const wildfireRadio = screen
      .getByRole('radiogroup', { name: '유형' })
      .querySelector('input[value="WILDFIRE"]') as HTMLInputElement;
    await user.click(wildfireRadio);

    expect(submit).toBeEnabled();
  });

  it('요청_성공시_generate_result_navigate', async () => {
    let body: unknown;
    mock.onPost('/generate/background').reply((config) => {
      body = JSON.parse(config.data ?? '{}');
      return [
        201,
        {
          success: true,
          data: { jobId: 555 },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithProviders(
      <BackgroundGenerateModal
        open
        video={baseVideo}
        frames={baseFrames}
        onClose={onClose}
      />,
      {
        initialEntries: ['/video/completed'],
        routes: [
          {
            path: '/video/completed',
            element: (
              <BackgroundGenerateModal
                open
                video={baseVideo}
                frames={baseFrames}
                onClose={onClose}
              />
            ),
          },
          {
            path: '/generate/result/:jobId',
            element: <div>GENERATE_RESULT_PAGE</div>,
          },
        ],
      },
    );

    const frame1 = screen.getByTestId('frame-pair-1').querySelector('input')!;
    await user.click(frame1);
    const radioGroup = screen.getByRole('radiogroup', { name: '유형' });
    const flood = within(radioGroup).getByDisplayValue('FLOOD') as HTMLInputElement;
    await user.click(flood);

    const submit = screen.getByTestId('bg-modal-submit');
    await user.click(submit);

    await waitFor(() => {
      expect(body).toMatchObject({ videoId: 10, srcSn: 1, genType: 'FLOOD' });
    });
    await waitFor(() => {
      expect(screen.getByText('GENERATE_RESULT_PAGE')).toBeInTheDocument();
    });
  });
});
