// LabelingPage — 비식별 누락 신고 버튼 통합 + 잠금 영상 UI 검증.
//
// 시나리오:
//  - WORKER + DEID 프레임: 헤더에 [비식별 누락 신고] 버튼 노출
//  - lockSttsCd=LOCKED_FOR_REDEIDENT: 상단 배너 표시 + 저장 버튼 비활성 + 신고 버튼 비활성
//  - frameImageType=RAW (REVIEWER 가 원본 보기): 신고 버튼 비활성
//  - PORTAL 모드: 신고 버튼 미노출

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

function labelsPayload(srcSn: number, opts: { frameImageType?: string; lockSttsCd?: string | null } = {}) {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn,
      videoId: 7,
      frameImageType: opts.frameImageType ?? 'DEID',
      lockSttsCd: opts.lockSttsCd ?? null,
      siblings: [{ srcSn, frameNo: 0 }],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

describe('LabelingPage 비식별 누락 신고 통합', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/image').reply(200, new Blob());
    mock.onGet('/frames/301/image').reply(200, new Blob());
    mock.onGet('/frames/302/image').reply(200, new Blob());
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('WORKER_+_DEID_프레임에서_비식별_누락_신고_버튼_노출', async () => {
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300, { frameImageType: 'DEID' }));

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: /비식별 누락 신고/ }),
      ).toBeInTheDocument();
    });
  });

  it('lockSttsCd_LOCKED_FOR_REDEIDENT_시_배너_표시_+_저장_버튼_비활성', async () => {
    mock
      .onGet('/frames/301/labels')
      .reply(200, labelsPayload(301, { lockSttsCd: 'LOCKED_FOR_REDEIDENT' }));

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/301'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 배너 — '비식별 재처리 중' 안내 (라벨 응답 도착 후 렌더)
    await waitFor(() => {
      expect(screen.getByTestId('deident-locked-banner')).toBeInTheDocument();
    });
    expect(screen.getByTestId('deident-locked-banner')).toHaveTextContent(
      /비식별 재처리 중/,
    );

    // 헤더 저장 버튼 비활성 (DarkToolbar 의 저장 버튼과 구분 — testid 사용)
    const saveBtn = screen.getByTestId('label-header-save');
    expect(saveBtn).toBeDisabled();

    // 신고 버튼 비활성 (이미 잠금)
    const reportBtn = screen.getByRole('button', { name: /비식별 누락 신고/ });
    expect(reportBtn).toBeDisabled();
  });

  it('lockSttsCd_정상상태_시_배너_미표시_+_저장_버튼_활성', async () => {
    mock.onGet('/frames/302/labels').reply(200, labelsPayload(302, { lockSttsCd: null }));

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/302'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 헤더 렌더 대기 — 저장 버튼이 나타나야 데이터 로딩 완료
    const saveBtn = await screen.findByTestId('label-header-save');
    expect(screen.queryByTestId('deident-locked-banner')).not.toBeInTheDocument();
    expect(saveBtn).not.toBeDisabled();
  });

  it('PORTAL_모드_시_비식별_누락_신고_버튼_미노출', async () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /비식별 누락 신고/ })).toBeNull();
  });
});
