// Phase 10(축소) — 포털 트랙 rename/머지 미제공 회귀 가드.
// 포털은 트랙 번호 변경·병합을 제공하지 않으므로 내부 전용 mergeTracks(/v1/videos/{rawSn}/tracks/merge,
// PORTAL 채널 403)를 절대 호출하면 안 된다. 버튼 숨김 + 핸들러 no-op 가드 이중 안전을 검증한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

// 내부 전용 트랙 병합 API 를 스파이 — 포털 모드에서 절대 호출되면 안 된다.
const mergeTracksSpy = vi.fn();
vi.mock('@/features/label/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/label/api')>();
  return { ...actual, mergeTracks: (...args: unknown[]) => mergeTracksSpy(...args) };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

// 트랙 번호(trackId)가 있는 라벨 — 내부 모드라면 2회차 rename 시 mergeTracks 로 이어질 데이터.
const labelsPayload = {
  success: true,
  data: {
    frameNo: 1,
    srcSn: 555,
    videoId: 5,
    siblings: [{ srcSn: 555, frameNo: 1 }],
    // BE portal 라벨 raw 아이템 형태(lblTypeCd + points) — 포털 로더의 points 필터를 통과해야 렌더된다.
    labels: [
      {
        id: 1,
        frameNo: 1,
        classId: 1,
        label: 'person',
        lblTypeCd: 'BBOX',
        autoLblYn: 'Y',
        trackId: '42',
        points: [
          [0, 0],
          [10, 10],
        ],
      },
    ],
  },
  message: null,
  errorCode: null,
};

describe('포털모드_rename_핸들러_내부API_미호출', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mergeTracksSpy.mockClear();
    mock = new MockAdapter(apiClient);
    // 런타임 구성 값 — 하드코딩 시크릿 회피(테스트 전용 더미).
    useAuthStore.setState({
      token: `dummy-${Date.now()}`,
      claims: { sub: 'u-99', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    mock.onGet('/portal/frames/555/labels').reply(200, labelsPayload);
    mock.onGet('/portal/frames/555/image').reply(200, new Blob([new Uint8Array([1])]));
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: {
        srcSn: 555,
        frameNo: 1,
        imageUrl: '',
        imageWidth: 0,
        imageHeight: 0,
        vlmText: '',
        stateChanges: [],
      },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function renderPortalLabel() {
    return renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/555'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
  }

  it('포털모드_트랙_rename_버튼_미노출_및_mergeTracks_미호출', async () => {
    renderPortalLabel();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    // 객체 트리가 렌더될 때까지 대기(라벨 로드 후 #1 선택 버튼 노출 — 순번은 index, track_id 42 는 별도 chip).
    await waitFor(() =>
      expect(screen.getByLabelText(/#1 선택$/)).toBeInTheDocument(),
    );

    // 이중 안전 ① — 포털 모드에서 연필(트랙 ID 변경) 버튼 진입 자체 차단.
    expect(screen.queryByLabelText(/트랙 ID 변경$/)).toBeNull();
    // 이중 안전 ② — 내부 전용 mergeTracks(/v1/videos/**/tracks/merge, PORTAL 403) 절대 미호출.
    expect(mergeTracksSpy).not.toHaveBeenCalled();
  });
});
