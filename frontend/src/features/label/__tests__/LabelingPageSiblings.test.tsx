// LabelingPage — siblings 응답으로 영상 전체 프레임 표시 및 프레임 이동 검증.
// react-konva 는 jsdom 에서 실제 렌더링 안 됨 → 모킹.
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

function labelsPayloadFor(srcSn: number, frameNo: number) {
  return {
    success: true,
    data: {
      frameNo,
      srcSn,
      videoId: 7,
      // 5 프레임 영상: srcSn 200~204, frameNo 0~4
      siblings: [
        { srcSn: 200, frameNo: 0 },
        { srcSn: 201, frameNo: 1 },
        { srcSn: 202, frameNo: 2 },
        { srcSn: 203, frameNo: 3 },
        { srcSn: 204, frameNo: 4 },
      ],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

describe('LabelingPage siblings 표시', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    // 영상의 모든 프레임에 대해 동일한 siblings 응답.
    [200, 201, 202, 203, 204].forEach((sn, idx) => {
      mock.onGet(`/frames/${sn}/labels`).reply(200, labelsPayloadFor(sn, idx));
      // useImageBlob 가 호출하는 이미지 엔드포인트 — 빈 blob 으로 통과.
      mock.onGet(`/frames/${sn}/image`).reply(200, new Blob());
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('siblings_5건이면_썸네일_strip에_5건_렌더', async () => {
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/200'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());

    // FrameFilmstrip 의 listbox 안에 5개 option 렌더 — aria-label "프레임 0".."프레임 4"
    await waitFor(() => {
      expect(screen.getByRole('option', { name: '프레임 0' })).toBeInTheDocument();
      expect(screen.getByRole('option', { name: '프레임 4' })).toBeInTheDocument();
    });
  });

  it('siblings_썸네일_클릭시_해당_프레임_URL로_이동', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/200'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());

    // 처음에는 프레임 0 (srcSn=200) — useImageBlob 가 /frames/200/image 호출.
    // 프레임 2 (srcSn=202) 썸네일 클릭 → navigate('/label/202') → useLabels 재조회 → 프레임 2 응답 로딩.
    const frame2 = await screen.findByRole('option', { name: '프레임 2' });
    await user.click(frame2);

    // 새 라우트에서 frameNo 2 의 응답이 적용되어야 함 — strip 의 frame 2 가 선택된 상태(aria-selected=true)
    await waitFor(() => {
      const opt = screen.getByRole('option', { name: '프레임 2' });
      expect(opt.getAttribute('aria-selected')).toBe('true');
    });
  });

  // 회귀 방지: 프레임 전환 시 isLoading=true 가 되어 Spinner 가 보이면
  // DarkFrameSlider 가 unmount 되고 재생 인터벌이 끊긴다.
  // useLabels 의 placeholderData: keepPreviousData 로 이전 data 가 유지되어야 함.
  it('siblings_프레임_전환시_로딩_Spinner_안_보임_(keepPreviousData)', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/200'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 첫 fetch 완료 대기 — strip 이 렌더된 상태
    await screen.findByRole('option', { name: '프레임 0' });
    expect(screen.queryByText('라벨 로딩 중...')).not.toBeInTheDocument();

    // 다른 프레임으로 전환 — 새 fetch 트리거되지만 Spinner 분기는 타지 않아야 함
    const frame2 = await screen.findByRole('option', { name: '프레임 2' });
    await user.click(frame2);

    // 새 fetch 진행 중에도 Spinner 가 노출되지 않고 strip 도 유지됨
    expect(screen.queryByText('라벨 로딩 중...')).not.toBeInTheDocument();
    expect(screen.getByRole('option', { name: '프레임 0' })).toBeInTheDocument();

    // 새 응답 적용 완료
    await waitFor(() => {
      const opt = screen.getByRole('option', { name: '프레임 2' });
      expect(opt.getAttribute('aria-selected')).toBe('true');
    });
    expect(screen.queryByText('라벨 로딩 중...')).not.toBeInTheDocument();
  });
});
