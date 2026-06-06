// R17 이슈5 — 포털 라벨 로드 403(미승인/미노출 영상) 시 graceful 차단 화면 회귀 테스트.
// 빈 캔버스 UI 노출 대신 "접근할 수 없는 영상입니다" 안내 + 뒤로 가기 버튼이 떠야 한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    // eslint-disable-next-line react/display-name, @typescript-eslint/no-explicit-any
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

const forbiddenBody = {
  success: false,
  data: null,
  message: '데이터마트에 노출되지 않은 영상입니다.',
  errorCode: 'FORBIDDEN',
};

describe('포털 라벨 로드 403 graceful 차단', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u-77', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('포털_미승인_영상_403시_접근불가_안내_화면', async () => {
    // given: 포털 라벨 로드가 403 (미승인/미노출 영상)
    mock.onGet('/portal/frames/777/labels').reply(403, forbiddenBody);
    mock.onGet('/portal/frames/777/image').reply(403, forbiddenBody);

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/777'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // then: graceful 차단 화면 — 빈 캔버스(labeling-page) 대신 전용 안내 화면이 떠야 한다.
    await waitFor(() => {
      expect(screen.getByTestId('portal-forbidden-screen')).toBeInTheDocument();
    });
    expect(screen.getByText('접근할 수 없는 영상입니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '뒤로 가기' })).toBeInTheDocument();
    // 캔버스/도구바가 들어있는 본 화면은 렌더되지 않아야 한다.
    expect(screen.queryByTestId('labeling-page')).toBeNull();
  });
});
