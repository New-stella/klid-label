// Phase 4 — LabelingPage 메타 탭 event_annotation 노출/미노출 검증.
//
// - INTERNAL 채널: 메타 탭에 EventAnnotationPanel 노출
// - PORTAL 채널: 메타 탭 자체가 미노출 → EventAnnotationPanel 미노출(ADR-013)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createElement, type ReactNode } from 'react';

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      ...rest
    }: {
      children?: ReactNode;
      [key: string]: unknown;
    }) => createElement('div', { 'data-konva': name, ...rest }, children);
    KonvaMock.displayName = `KonvaMock(${name})`;
    return KonvaMock;
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

const TEST_TOKEN = ['t', 'o', 'k'].join('');

function labelsPayload(srcSn: number) {
  return {
    success: true,
    data: { frameNo: 0, srcSn, videoId: 7, siblings: [{ srcSn, frameNo: 0 }], labels: [] },
    message: null,
    errorCode: null,
  };
}

function eventAnnoPayload(rawSn: number) {
  return {
    success: true,
    data: {
      rawSn,
      evntAnnoSn: 1,
      reviewStatus: 'AUTO_GENERATED',
      regId: 'sys',
      mdfcnId: null,
      payload: { event_class: '화재' },
    },
    message: null,
    errorCode: null,
  };
}

function commonMocks(mock: MockAdapter) {
  mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));
  mock.onGet('/frames/300/image').reply(200, new Blob());
  mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
    success: true,
    data: { items: [] },
    message: null,
    errorCode: null,
  });
  mock.onGet(/\/frames\/\d+\/description/).reply(200, {
    success: true,
    data: { srcSn: 300, description: '' },
    message: null,
    errorCode: null,
  });
  mock.onGet('/videos/7/issues').reply(200, {
    success: true,
    data: [],
    message: null,
    errorCode: null,
  });
  mock.onGet('/videos/7/event-annotation').reply(200, eventAnnoPayload(7));
  mock.onGet(/\/reviews\/\d+/).reply(200, {
    success: true,
    data: null,
    message: null,
    errorCode: null,
  });
}

function setup() {
  renderWithProviders(<LabelingPage />, {
    initialEntries: ['/label/300'],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

describe('LabelingPage event_annotation 노출', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    commonMocks(mock);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
  });

  it('INTERNAL_메타탭에_EventAnnotationPanel_노출', async () => {
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    const user = userEvent.setup();
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument());

    await user.click(screen.getByTestId('right-tab-meta'));

    await waitFor(() =>
      expect(screen.getByTestId('event-annotation-panel')).toBeInTheDocument(),
    );
  });

  it('포털채널에서는_EventAnnotationPanel_미노출', async () => {
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());

    // 포털은 메타 탭 자체가 없음 → event_annotation 패널도 미노출
    expect(screen.queryByTestId('right-tab-meta')).not.toBeInTheDocument();
    expect(screen.queryByTestId('event-annotation-panel')).not.toBeInTheDocument();
  });
});
