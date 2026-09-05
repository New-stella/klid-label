/**
 * 포털 라벨링 화면 통합 회귀 가드 — **포털의 라벨링 화면은 하나뿐이다.**
 *
 * 이 파일이 고정하는 계약:
 *  1. 한 주소(`/portal/label/:id`)가 두 출처를 모두 연다. 출처 표기가 그 갈래를 정한다.
 *  2. 표기가 없거나 아는 값이 아니면 **데이터마트로 읽는다**(fail-closed — 지금까지의 동작).
 *  3. 폐기된 업로드 자산 라벨링 주소로 들어와도 **막히지 않는다** — 통합 주소로 갈아탄다.
 *  4. 자산 목록의 «라벨링» 은 통합 주소를 가리킨다(폐기 주소로 보내지 않는다).
 *  5. 이 화면은 **이동 탭에 뜨지 않는다** — 목적지가 아니라 목록에서 들어가는 몰입 편집 화면이다.
 *
 * @design SCREEN-029
 * @design SCREEN-034
 * @design NAV-002
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { useLocation } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

/*
 * 데이터마트 갈래의 본문은 대역으로 세운다 — 여기서 지켜야 하는 것은 «어느 갈래를 그리는가» 이지
 * 그 본문의 동작이 아니다(그쪽 동작은 라벨링 화면 시험이 따로 지킨다). 대역을 쓰면 두 갈래가
 * 실제로 갈리는지가 드러난다 — 한쪽으로 고정하는 변이가 곧바로 빨간불이 된다.
 */
vi.mock('@/pages/label/LabelingPage', () => ({
  LabelingPage: () => <div data-testid="datamart-branch" />,
}));

import { apiClient } from '@/lib/api/client';
import { PortalContentTabs } from '@/components/layout/PortalContentTabs';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';

import { PortalLabelingPage } from '../PortalLabelingPage';
import { PortalUploadLabelingRedirect } from '../PortalUploadLabelingRedirect';
import { PortalUploadPage } from '../PortalUploadPage';

// 테스트용 더미 인증값(비밀 아님 — 시크릿 스캐너 오탐 회피용 조합).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function uploadDetail() {
  return {
    uldSn: 1,
    uldTypeCd: 'VIDEO',
    orgnlFileNm: 'clip.mp4',
    fileSz: 1024,
    mimeTypeNm: 'video/mp4',
    uldSttsCd: 'READY',
    frmeCnt: 1,
    vdoLenSec: 10,
    fps: 30,
    regDt: '2026-07-17T00:00:00',
    mdfcnDt: null,
    expiresAt: null,
    frames: [{ uldFrmeSn: 100, uldSn: 1, frmeNo: 0, regDt: '2026-07-17T00:00:00' }],
  };
}

/** 지금 서 있는 주소를 그대로 드러내는 탐침 — 갈아타기의 목적지를 눈으로 확인한다. */
function LocationProbe() {
  const { pathname, search } = useLocation();
  return <div data-testid="here">{`${pathname}${search}`}</div>;
}

describe('포털 라벨링 화면 통합', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    mock.onGet('/manage/labels').reply(200, ok([]));
    mock.onGet(/\/portal\/uploads\/frames\/\d+\/image/).reply(200, new Blob());
    mock.onGet('/portal/uploads/1').reply(200, ok(uploadDetail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:x'),
      revokeObjectURL: vi.fn(),
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    vi.restoreAllMocks();
  });

  // ── 1·2. 한 주소가 두 출처를 연다 ───────────────────────────────────────
  it('업로드_출처_표기가_있으면_업로드_자산_갈래를_연다', async () => {
    // given / when
    renderWithProviders(<PortalLabelingPage />, {
      initialEntries: ['/portal/label/1?source=upload'],
      routes: [{ path: '/portal/label/:id', element: <PortalLabelingPage /> }],
    });

    // then: 업로드 자산 갈래가 서고 데이터마트 갈래는 서지 않는다
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    expect(screen.getByText('clip.mp4')).toBeInTheDocument();
    expect(screen.queryByTestId('datamart-branch')).toBeNull();
  });

  it('출처_표기가_없으면_데이터마트_갈래를_연다', async () => {
    // given / when
    renderWithProviders(<PortalLabelingPage />, {
      initialEntries: ['/portal/label/1'],
      routes: [{ path: '/portal/label/:id', element: <PortalLabelingPage /> }],
    });

    // then
    expect(await screen.findByTestId('datamart-branch')).toBeInTheDocument();
    // then: 업로드 자산 창구는 건드리지 않는다
    expect(mock.history.get.some((r) => r.url === '/portal/uploads/1')).toBe(false);
  });

  it('아는_값이_아닌_출처_표기는_데이터마트로_읽는다_fail_closed', async () => {
    // given / when
    renderWithProviders(<PortalLabelingPage />, {
      initialEntries: ['/portal/label/1?source=weird'],
      routes: [{ path: '/portal/label/:id', element: <PortalLabelingPage /> }],
    });

    // then
    expect(await screen.findByTestId('datamart-branch')).toBeInTheDocument();
  });

  // ── 3. 폐기된 주소로 들어와도 막히지 않는다 ────────────────────────────
  it('폐기된_업로드_라벨링_주소는_통합_주소로_갈아탄다', async () => {
    // given / when
    renderWithProviders(<LocationProbe />, {
      initialEntries: ['/portal/uploads/7/label'],
      routes: [
        { path: '/portal/uploads/:uldSn/label', element: <PortalUploadLabelingRedirect /> },
        { path: '/portal/label/:id', element: <LocationProbe /> },
      ],
    });

    // then
    expect(await screen.findByTestId('here')).toHaveTextContent('/portal/label/7?source=upload');
  });

  it('자산_번호로_읽히지_않는_폐기_주소는_자산_목록으로_보낸다', async () => {
    // given / when
    renderWithProviders(<LocationProbe />, {
      initialEntries: ['/portal/uploads/abc/label'],
      routes: [
        { path: '/portal/uploads/:uldSn/label', element: <PortalUploadLabelingRedirect /> },
        { path: '/portal/uploads', element: <LocationProbe /> },
      ],
    });

    // then
    expect(await screen.findByTestId('here')).toHaveTextContent('/portal/uploads');
  });

  // ── 4. 목록의 진입은 통합 주소를 가리킨다 ──────────────────────────────
  it('자산_목록의_라벨링은_통합_주소를_가리킨다', async () => {
    // given
    mock.onGet('/portal/uploads').reply(
      200,
      ok({
        content: [
          {
            uldSn: 1,
            uldTypeCd: 'VIDEO',
            orgnlFileNm: 'clip.mp4',
            fileSz: 1024,
            mimeTypeNm: 'video/mp4',
            uldSttsCd: 'READY',
            frmeCnt: 1,
            frmeSn: null,
            regDt: '2026-07-17T00:00:00',
            expiresAt: null,
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      }),
    );

    // when
    renderWithProviders(<PortalUploadPage />, { initialEntries: ['/portal/uploads'] });

    // then
    const link = await screen.findByRole('link', { name: '라벨링' });
    expect(link).toHaveAttribute('href', '/portal/label/1?source=upload');
    // then: 폐기된 주소로는 보내지 않는다
    expect(link.getAttribute('href')).not.toContain('/uploads/1/label');
  });

  // ── 5. 몰입 편집 화면이라 이동 탭에 뜨지 않는다 ────────────────────────
  it('통합_라벨링_화면에서는_이동_탭을_그리지_않는다', () => {
    // given / when: 두 갈래 모두 확인한다 — 표기가 붙었다고 탭이 새어 나오면 안 된다
    const { unmount } = renderWithProviders(<PortalContentTabs />, {
      initialEntries: ['/portal/label/1?source=upload'],
    });

    // then
    expect(screen.queryByRole('navigation', { name: '포털 이동 탭' })).toBeNull();
    unmount();

    renderWithProviders(<PortalContentTabs />, { initialEntries: ['/portal/label/1'] });
    expect(screen.queryByRole('navigation', { name: '포털 이동 탭' })).toBeNull();
  });

  it('목적지_화면에서는_이동_탭이_그대로_뜬다_과잉_차단_가드', () => {
    // given / when: 위 가드가 «항상 안 뜬다» 로 굳지 않도록 반대 축을 함께 둔다
    renderWithProviders(<PortalContentTabs />, { initialEntries: ['/portal/uploads'] });

    // then
    expect(screen.getByRole('navigation', { name: '포털 이동 탭' })).toBeInTheDocument();
  });
});
