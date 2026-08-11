// LabelingPage — 버전 진입점 검증 (R6/D4).
//
// 구 검증 대상은 **헤더 [히스토리] 버튼 → 우측 인라인 패널**이었다. 그 진입점은 폐지됐고,
// 지금은 캔버스 상단 옵션바의 [버전] 버튼이 「시작 버전 선택」 모달을 열며 그 안에 프레임 버전
// 이력(버전 목록 · 버전 간 diff · 작업본 diff · 롤백)이 들어 있다.
//
// ⚠ 이 파일은 **화면에서 그 진입점에 실제로 닿는가**만 본다. 모달 내부 동작은
//   `features/version/__tests__/StartVersionModal.test.tsx` 가 본다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

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

describe('LabelingPage 버전 진입점(시작 버전 선택)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));
    mock.onGet('/frames/300/image').reply(200, new Blob());
    // 영상 산출 버전 — 기본은 빈 목록이라 진입 시 모달이 자동으로 뜨지 않는다(둘 이상일 때만 뜬다).
    mock.onGet(/\/videos\/\d+\/versions/).reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
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

  it('버전_버튼_클릭시_시작_버전_선택이_열린다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());

    // 진입점이 노출되고 (INTERNAL 채널), 산출 버전이 0건이라 모달은 아직 자동으로 뜨지 않는다
    const openBtn = await screen.findByTestId('start-version-open');
    expect(screen.queryByTestId('start-version-modal')).toBeNull();

    await user.click(openBtn);

    // 모달 노출
    await waitFor(() => {
      expect(screen.getByTestId('start-version-modal')).toBeInTheDocument();
    });
  });

  it('빈_버전_응답으로도_모달이_정상_렌더_(500_회귀_방어)', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await user.click(await screen.findByTestId('start-version-open'));

    // 재배치된 프레임 버전 이력을 펼친다 — 기본 탭이 '버전'이라 빈 메시지가 바로 보인다.
    await user.click(await screen.findByTestId('start-version-history-toggle'));

    await waitFor(() => {
      // 빈 메시지 노출
      // [2026-08-06] 다른 축이 EmptyState 메시지를 변경했다.
      // 이 테스트의 원 취지(빈 응답에서도 500 없이 렌더)는 유지하고 메시지만 실제 구현에 맞춘다.
      expect(screen.getByText('버전 이력이 없습니다')).toBeInTheDocument();
    });
  });

  it('포털_채널은_버전_진입점_미노출', async () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    // 진입점·모달 모두 없음 (포털은 버전관리 미제공 — ADR-013)
    expect(screen.queryByTestId('start-version-open')).toBeNull();
    expect(screen.queryByTestId('start-version-modal')).toBeNull();
    expect(screen.queryByTestId('history-toggle')).toBeNull();
  });
});
