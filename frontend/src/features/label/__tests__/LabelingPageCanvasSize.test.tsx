// LabelingPage — 캔버스 스테이지 크기는 실제 캔버스 영역을 따른다.
//
// 회귀 배경(2026-09-15 포털 개발망 실측): 캔버스 영역은 813×291 인데 스테이지가 1280×720 으로
// 그려져 프레임 가운데만 잘려 보였고 「화면 맞춤」도 보이지 않는 1280×720 에 맞췄다.
// 원인은 측정 훅이 **첫 렌더 한 번만** 요소를 찾던 것이다 — 첫 렌더는 조회 중 화면이라 캔버스
// 영역이 아직 없고, 그 뒤 영역이 생겨도 다시 붙지 않아 크기가 0 으로 남아 기본값으로 떨어졌다.
// 관제 채널은 큰 모니터에서 영역이 기본값과 비슷해 드러나지 않았을 뿐 같은 결함이다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { waitFor } from '@testing-library/react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';

const MEASURED = { width: 813, height: 291 };

describe('LabelingPage — 캔버스 스테이지 크기', () => {
  let mock: MockAdapter;
  let rectSpy: { mockRestore: () => void };

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    mock.onGet('/frames/200/labels').reply(200, {
      success: true,
      data: { frameNo: 0, srcSn: 200, videoId: 7, siblings: [{ srcSn: 200, frameNo: 0 }], labels: [] },
      message: null,
      errorCode: null,
    });
    mock.onGet('/frames/200/image').reply(200, new Blob());
    rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      ...MEASURED,
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      right: MEASURED.width,
      bottom: MEASURED.height,
      toJSON: () => ({}),
    } as DOMRect);
  });

  afterEach(() => {
    rectSpy.mockRestore();
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
  });

  it('조회_중_화면을_거쳐_열려도_스테이지가_측정한_캔버스_영역_크기로_그려진다', async () => {
    const { container } = renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/200'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => {
      expect(container.querySelector('[data-konva="Stage"]')).not.toBeNull();
    });
    const stage = container.querySelector('[data-konva="Stage"]')!;
    expect(stage.getAttribute('width')).toBe(String(MEASURED.width));
    expect(stage.getAttribute('height')).toBe(String(MEASURED.height));
  });
});
