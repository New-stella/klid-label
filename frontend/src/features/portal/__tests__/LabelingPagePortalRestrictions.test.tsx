// 포털 채널 라벨링 화면 — 미노출 보장 회귀 테스트.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const labelsPayload = {
  success: true,
  data: {
    frameNo: 1,
    srcSn: 555,
    videoId: 5,
    siblings: [{ srcSn: 555, frameNo: 1 }],
    labels: [],
  },
  message: null,
  errorCode: null,
};

describe('포털 채널 라벨링 미노출', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u-99', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    // R16 — 포털 모드는 포털 전용 엔드포인트만 호출
    mock.onGet('/portal/frames/555/labels').reply(200, labelsPayload);
    mock.onGet('/portal/frames/555/image').reply(200, new Blob([new Uint8Array([1])]));
    // 시계열 메타 패널이 데이터를 받으면 비동기로 렌더되는 경로까지 활성화 —
    // 포털 모드 가드가 없으면 패널이 결국 노출되므로, mock 을 제공해 회귀를 확실히 잡는다.
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: {
        srcSn: 555,
        frameNo: 1,
        imageUrl: '',
        imageWidth: 0,
        imageHeight: 0,
        vlmText: '포털에 노출되면 안 되는 VLM 시계열 텍스트',
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

  it('포털_라벨링_화면_검수제출_버튼_미렌더', async () => {
    renderPortalLabel();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    expect(screen.queryByTestId('submit-review-button')).toBeNull();
    expect(screen.queryByRole('button', { name: '검수제출' })).toBeNull();
  });

  it('포털_라벨링_화면_VLM_메타_탭_미노출', async () => {
    renderPortalLabel();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    // useMeta 응답이 비동기로 들어와 시계열 메타 패널이 뒤늦게 렌더될 수 있으므로,
    // 메타 쿼리/렌더가 완료될 시간을 충분히 준 뒤에도 끝까지 미노출임을 보장한다.
    // (가드가 없으면 이 대기 후 "시계열 메타" 버튼이 나타나 RED 가 된다.)
    await new Promise((resolve) => setTimeout(resolve, 300));
    // VLM 객체 검증, 시계열 메타 탭은 외부 시스템 책임 — 포털 라벨링 UI에 노출되면 안됨.
    expect(screen.queryByRole('button', { name: /시계열 메타/ })).toBeNull();
    expect(screen.queryByText(/VLM/)).toBeNull();
    expect(screen.queryByText(/시계열 메타/)).toBeNull();
    expect(screen.queryByRole('tab', { name: /메타/ })).toBeNull();
  });

  // ADR-013 — 포털은 오토라벨링·SAM2·VLM·버전관리·검수 미제공. 서버의 포털 전용 SAM2 엔드포인트
  // (/v1/portal/frames/{srcSn}/sam2-*)도 제거됐으므로(BE PortalSam2RemovedTest) 도구를 노출하면
  // 사용자가 404 만 만난다. FE 게이팅은 UX 편의이고 실제 강제는 서버가 한다.
  it('포털_라벨링_도구바에_SAM2_분할_추적_스켈레톤이_노출되지_않는다', async () => {
    renderPortalLabel();
    // 로딩 화면도 labeling-page testid 를 갖는다 → 도구바가 실제 렌더될 때까지(바운딩박스 버튼) 대기.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '바운딩 박스' })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: 'AI 분할' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'AI 추적' })).toBeNull();
    expect(screen.queryByRole('button', { name: '스켈레톤' })).toBeNull();
    // 기본 도구(바운딩박스/폴리곤)는 그대로 노출 — 과잉 차단 회귀 가드.
    expect(screen.getByRole('button', { name: '바운딩 박스' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '폴리곤' })).toBeInTheDocument();
    // YOLO 파이프라인 오토라벨도 포털 미제공 — 계속 숨김(회귀 가드).
    expect(screen.queryByRole('button', { name: 'AI 탐지' })).toBeNull();
  });
});
