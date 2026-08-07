// LabelingPage — 우측 인라인 히스토리 패널 토글 검증.
// 헤더 [히스토리] 버튼 클릭 시 inline-history-panel 노출/숨김.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, ...rest }: any) =>
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

function labelsPayload(srcSn: number) {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn,
      videoId: 7,
      siblings: [{ srcSn, frameNo: 0 }],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

describe('LabelingPage 인라인 히스토리 패널 토글', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));
    mock.onGet('/frames/300/image').reply(200, new Blob());
    // 빈 버전 응답 — 새 영상 회귀 가드 케이스
    mock.onGet('/frames/300/versions').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
    // 히스토리 패널 기본 탭(변경 이력)이 마운트 시 label-history 를 조회 — 빈 페이지 모킹.
    mock.onGet(/\/frames\/\d+\/label-history/).reply(200, {
      success: true,
      data: { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0 },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('히스토리_버튼_클릭시_우측_패널_노출', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());

    // 토글 버튼이 노출되고 (INTERNAL 채널), 초기에는 패널 미렌더
    const toggle = await screen.findByTestId('history-toggle');
    expect(screen.queryByTestId('inline-history-panel')).toBeNull();

    await user.click(toggle);

    // 패널 노출
    await waitFor(() => {
      expect(screen.getByTestId('inline-history-panel')).toBeInTheDocument();
    });
    expect(screen.getByTestId('history-panel')).toBeInTheDocument();

    // 한 번 더 클릭 → 토글 닫힘
    await user.click(toggle);
    await waitFor(() => {
      expect(screen.queryByTestId('inline-history-panel')).toBeNull();
    });
  });

  it('빈_버전_응답으로도_패널이_정상_렌더_(500_회귀_방어)', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await user.click(await screen.findByTestId('history-toggle'));

    // 통합 히스토리 패널의 기본 탭은 "변경 이력" — 버전(커밋) 빈 메시지는 "버전" 탭에서 확인.
    await user.click(await screen.findByTestId('history-tab-versions'));

    await waitFor(() => {
      // 빈 메시지 노출
      // [2026-08-06] 다른 축이 EmptyState 메시지를 변경했다.
      // 이 테스트의 원 취지(빈 응답에서도 500 없이 렌더)는 유지하고 메시지만 실제 구현에 맞춘다.
      expect(screen.getByText('버전 이력이 없습니다')).toBeInTheDocument();
    });
  });

  it('포털_채널은_히스토리_버튼_및_패널_미노출', async () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    // 토글 버튼/링크 모두 없음
    expect(screen.queryByTestId('history-toggle')).toBeNull();
    expect(screen.queryByRole('link', { name: /히스토리/ })).toBeNull();
  });
});
