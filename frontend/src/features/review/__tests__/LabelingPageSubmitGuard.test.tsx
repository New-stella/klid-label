// R6-B 회귀 — 완료/검수불가 상태에서 검수제출 버튼이 disabled 인지 검증.
// react-konva는 jsdom에서 실제 렌더링 안 됨 → 모킹.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

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

function labelsPayload(videoId: number) {
  return {
    success: true,
    data: { frameNo: 0, srcSn: 100, videoId, labels: [], siblings: [] },
    message: null,
    errorCode: null,
  };
}

function reviewPayload(videoId: number, status: string) {
  return {
    success: true,
    data: {
      id: 1,
      videoId,
      cctvName: 'CCTV-1',
      workerId: 7,
      workerName: '홍길동',
      submittedAt: '2026-05-07T10:00:00Z',
      labelCount: 0,
      status,
    },
    message: null,
    errorCode: null,
  };
}

describe('LabelingPage 검수제출 버튼 상태 가드 (R6-B)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function renderForStatus(status: string) {
    mock.onGet('/frames/100/labels').reply(200, labelsPayload(18));
    mock.onGet('/reviews/18').reply(200, reviewPayload(18, status));
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/100'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
  }

  it('R12_2_COMPLETED_상태면_재검수_제출_버튼_enabled_라벨_재검수_제출', async () => {
    // given/when — 검수완료(APPROVED→COMPLETED) 상태. 재검수 진입 허용으로 변경.
    renderForStatus('COMPLETED');
    // then — 버튼 enabled + 문구 '재검수 제출'
    const btn = await screen.findByTestId('submit-review-button');
    await waitFor(() => {
      expect(btn).not.toBeDisabled();
    });
    expect(btn).toHaveTextContent('재검수 제출');
    expect(btn).toHaveAttribute('aria-label', '재검수 제출');
  });

  it('R12_2_REVIEW_PENDING_상태면_검수제출_버튼_disabled_유지', async () => {
    // PENDING(=이미 제출/검수 대기)은 여전히 차단.
    renderForStatus('REVIEW_PENDING');
    const btn = await screen.findByTestId('submit-review-button');
    await waitFor(() => {
      expect(btn).toBeDisabled();
    });
  });

  it('REVIEWING_상태면_검수제출_버튼_disabled', async () => {
    renderForStatus('REVIEWING');
    const btn = await screen.findByTestId('submit-review-button');
    await waitFor(() => {
      expect(btn).toBeDisabled();
    });
  });

  it('ASSIGNED_상태면_검수제출_버튼_문구는_검수제출_유지', async () => {
    renderForStatus('ASSIGNED');
    const btn = await screen.findByTestId('submit-review-button');
    await waitFor(() => {
      expect(btn).not.toBeDisabled();
    });
    expect(btn).toHaveTextContent('검수제출');
  });

  it('ASSIGNED_상태면_검수제출_버튼_enabled', async () => {
    // 배치 완료 후 작업 상태 ASSIGNED — 제출 가능
    renderForStatus('ASSIGNED');
    const btn = await screen.findByTestId('submit-review-button');
    await waitFor(() => {
      expect(btn).not.toBeDisabled();
    });
  });

  it('REJECTED_상태면_검수제출_버튼_enabled', async () => {
    renderForStatus('REJECTED');
    const btn = await screen.findByTestId('submit-review-button');
    await waitFor(() => {
      expect(btn).not.toBeDisabled();
    });
  });
});
