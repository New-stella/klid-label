// R16 — 포털 라벨링 데이터 경로 분리 회귀 테스트.
// 포털 모드(channel='PORTAL')에서 LabelingPage 가:
//  1) 내부 전용 API(/frames/{id}/labels, /frames/{id}/image)를 호출하지 않고
//  2) 포털 전용 API(/portal/frames/{id}/labels, /portal/frames/{id}/image)만 호출
//  3) 저장 시 POST /portal/user-labels 페이로드를 전송
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const portalLabelsPayload = {
  success: true,
  data: {
    frameNo: 0,
    srcSn: 777,
    videoId: 7,
    siblings: [{ srcSn: 777, frameNo: 0 }],
    labels: [
      {
        id: 1,
        lblTypeCd: 'BBOX',
        label: 'person',
        points: [
          [1, 2],
          [3, 4],
        ],
      },
    ],
  },
  message: null,
  errorCode: null,
};

describe('포털 라벨링 데이터 경로', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u-99', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    // 포털 전용 엔드포인트만 mock — 내부 전용은 일부러 mock 하지 않아 호출 시 네트워크 에러로 드러나게 함
    mock.onGet('/portal/frames/777/labels').reply(200, portalLabelsPayload);
    mock.onGet('/portal/frames/777/image').reply(200, new Blob([new Uint8Array([1, 2, 3])]));
    mock.onPost('/portal/user-labels').reply(201, {
      success: true,
      data: {
        userLblSn: 1,
        sourceRawSn: 7,
        sourceSrcSn: 777,
        lblTypeCd: 'BBOX',
        label: 'person',
        points: '[[1,2],[3,4]]',
        createdAt: '2026-06-06T00:00:00',
      },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
  });

  function renderPortalLabel() {
    return renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/777'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
  }

  it('포털_모드_라벨로드는_portal_API만_호출_내부API_미호출', async () => {
    renderPortalLabel();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());

    await waitFor(() => {
      const portalLabelCall = mock.history.get.some((r) => r.url === '/portal/frames/777/labels');
      expect(portalLabelCall).toBe(true);
    });

    // 내부 전용 API 는 절대 호출되지 않아야 한다.
    const internalLabelCall = mock.history.get.some((r) => r.url?.startsWith('/frames/'));
    expect(internalLabelCall).toBe(false);
  });

  it('포털_모드_이미지도_portal_엔드포인트로_요청', async () => {
    renderPortalLabel();
    await waitFor(() => {
      const imgCall = mock.history.get.some((r) => r.url === '/portal/frames/777/image');
      expect(imgCall).toBe(true);
    });
    const internalImg = mock.history.get.some((r) => r.url?.match(/^\/frames\/\d+\/image$/));
    expect(internalImg).toBe(false);
  });

  it('★캔버스_상단_옵션바의_저장_버튼이_포털_전용_경로로_라우팅한다', async () => {
    // 저장 버튼이 좌측 도구바에서 옵션바로 이관된 뒤에도 portalMode 라우팅이 유지되는지 고정한다.
    // 옵션바가 자체 저장(내부 PUT)을 하면 포털 채널에서 403 이 나므로 화면의 저장 절차에 위임해야 한다.
    renderPortalLabel();
    await waitFor(() => expect(screen.getByTestId('canvas-option-bar')).toBeInTheDocument());

    const bar = screen.getByTestId('canvas-option-bar');
    const save = screen.getByRole('button', { name: '저장' });
    expect(bar.contains(save)).toBe(true);

    fireEvent.click(save);

    await waitFor(() =>
      expect(mock.history.post.some((r) => r.url === '/portal/user-labels')).toBe(true),
    );
    // 내부 전용 저장(PUT /frames/{id}/labels)은 절대 호출되지 않아야 한다.
    expect(mock.history.put.some((r) => r.url?.startsWith('/frames/'))).toBe(false);
  });
});
