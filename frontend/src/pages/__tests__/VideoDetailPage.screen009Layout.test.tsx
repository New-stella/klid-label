// 영상 상세 — SCREEN-009 확정 화면정의서 골격 회귀 가드. [@design SCREEN-009]
//
// ★ 무엇을 지키는 테스트인가
//   ① 탭은 **좌측 세로 레일**이다 — 확정 디자인(design-main.html `.detail-layout`)이 2컬럼이고,
//      그 전환이 콘텐츠 폭을 확보해 처리 단계 전폭 밴드가 성립한다. 가로 탭으로 되돌리면 처리
//      단계가 2열 그리드 셀에 압축돼 노드·캡션이 판독 불가가 된다.
//   ② '재비식별 요청' 진입점은 **없다** — screen_spec v29 확정(버튼 + 확인 다이얼로그 제거,
//      design-notes 변경 로그 2026-08-11). 서버 경로(POST /videos/{rawSn}/redeident)는 그대로다.
//
// ⚠ 재비식별 버튼의 구 노출 조건(REVIEWER + reviewSttsCd=APPROVED + deIdntfYn!=='Y')을 **그대로
//   만족시키는 응답**으로 검증한다. 조건을 안 맞춘 응답으로 "안 보인다"를 단언하면 제거가 아니라
//   가드에 걸린 것을 보고 통과하는 거짓 가드가 된다.
// ⚠ 「비식별」이라는 낱말 자체는 비식별 이력 패널의 **표시 라벨**로 남아 있다(액션 아님).
//   그래서 문자열이 아니라 **role=button** 으로 좁혀 단언한다.

import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { VideoDetailPage } from '@/pages/VideoDetailPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn() };
});

// 구 재비식별 버튼의 노출 조건을 모두 만족하는 영상(검수완료 + 비식별 미완).
const DETAIL = {
  rawSn: 42,
  id: 42,
  cctvName: '종로구 CCTV-01',
  vmsCctvId: 'CCTV-42',
  eventTypeCd: 'FALL',
  status: 'COMPLETED',
  reviewSttsCd: 'APPROVED',
  deIdntfYn: 'N',
  resolution: '1920x1080',
  durationSec: 35,
  capturedAt: '2026-01-15T09:30:00',
  framePreviews: [],
  stages: [],
  deidentHistory: [],
};

function renderPage() {
  return renderWithProviders(<VideoDetailPage />, {
    initialEntries: ['/videos/42'],
    routes: [{ path: '/videos/:id', element: <VideoDetailPage /> }],
  });
}

describe('VideoDetailPage SCREEN-009 확정 골격', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: DETAIL,
      message: null,
      errorCode: null,
    });
    useAuthStore.setState({
      token: 'placeholder-jwt',
      claims: { sub: 'u-1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('탭은_좌측_세로_레일이다_3개_탭이_vertical_tablist_에_있다', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('종로구 CCTV-01')).toBeInTheDocument());

    const tablist = screen.getByRole('tablist', { name: '영상 상세 탭' });
    expect(tablist).toHaveAttribute('aria-orientation', 'vertical');
    expect(within(tablist).getAllByRole('tab')).toHaveLength(3);

    // 레일 헤딩 — 확정 디자인의 `.tab-rail-heading`.
    expect(screen.getByRole('navigation', { name: '상세 정보' })).toContainElement(tablist);
  });

  it('처리_단계는_메타_그리드_셀이_아니라_전폭_밴드다', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('종로구 CCTV-01')).toBeInTheDocument());

    // 메타 그리드(<dl>)에는 '처리 단계' 항목이 없다 — 전폭 밴드로 빠져나갔다.
    const metaLabels = screen.getAllByRole('term').map((dt) => dt.textContent);
    expect(metaLabels).toContain('해상도');
    expect(metaLabels).not.toContain('처리 단계');
    expect(screen.getByText('처리 단계')).toBeInTheDocument();
  });

  it('재비식별_요청_버튼은_노출_조건을_만족해도_존재하지_않는다', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('종로구 CCTV-01')).toBeInTheDocument());

    expect(screen.queryByRole('button', { name: /재비식별/ })).not.toBeInTheDocument();
    // 요청 자체가 나가지 않는다(버튼이 없으므로 자동으로 성립하지만, 경로 부활을 함께 막는다).
    expect(mock.history.post.filter((h) => h.url === '/videos/42/redeident')).toHaveLength(0);
  });
});
