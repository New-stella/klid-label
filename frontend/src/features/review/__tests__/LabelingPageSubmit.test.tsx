// react-konva는 jsdom에서 실제 렌더링 안 됨 → 모킹.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, ...rest }: any) =>
      // eslint-disable-next-line react/no-children-prop
      React.createElement('div', { 'data-konva': name, ...rest }, children);
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
    Group: passthrough('Group'),
  };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const labelsPayload = {
  success: true,
  data: { frameNo: 0, srcSn: 100, labels: [] },
  message: null,
  errorCode: null,
};

describe('LabelingPage 검수제출 버튼', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/frames/100/labels').reply(200, labelsPayload);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('WORKER가_검수제출_버튼_클릭시_videos_id_submit_호출', async () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });

    let submitted = false;
    mock.onPost('/videos/100/submit').reply(() => {
      submitted = true;
      return [
        200,
        {
          success: true,
          data: {
            id: 50,
            videoId: 100,
            cctvName: 'CCTV-1',
            workerId: 7,
            workerName: '홍길동',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 0,
            status: 'REVIEW_PENDING',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/100'],
      routes: [
        { path: '/label/:id', element: <LabelingPage /> },
        { path: '/task', element: <div>TASK_LIST</div> },
      ],
    });

    const btn = await screen.findByTestId('submit-review-button');
    await user.click(btn);

    await waitFor(() => {
      expect(submitted).toBe(true);
    });
    await waitFor(() => {
      expect(screen.getByText('TASK_LIST')).toBeInTheDocument();
    });
  });

  it('REVIEWER에게는_검수제출_버튼_미노출', async () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/100'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('labeling-page')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('submit-review-button')).not.toBeInTheDocument();
  });
});
