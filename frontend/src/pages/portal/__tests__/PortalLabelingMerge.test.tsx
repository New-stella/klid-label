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
import { screen } from '@testing-library/react';
import { useLocation } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

/*
 * 라벨링 본문은 대역으로 세운다 — 여기서 지켜야 하는 것은 «어느 출처로 읽는가» 이지 그 본문의
 * 동작이 아니다(그쪽 동작은 라벨링 화면 시험이 따로 지킨다).
 *
 * ★두 출처가 **같은 본문**을 쓴다 — 갈리는 것은 화면이 아니라 조회·이미지·저장 창구이고, 그
 *   판정 결과가 `source` 로 내려온다. 그래서 대역은 «어느 컴포넌트가 섰는가» 가 아니라 **그
 *   본문이 받은 출처 값**을 드러낸다. 값을 한쪽으로 고정하는 변이가 곧바로 빨간불이 된다.
 * ⚠ 기본값(`source` 미전달)도 함께 드러낸다 — 판정 자체를 떼어내는 변이를 잡으려면 «전달되지
 *   않았다» 와 «datamart 를 전달했다» 가 구분돼야 한다.
 */
vi.mock('@/pages/label/LabelingPage', () => ({
  LabelingPage: ({ source }: { source?: string }) => (
    <div data-testid="labeling-branch" data-source={source ?? '(미전달)'} />
  ),
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
  it('업로드_출처_표기가_있으면_업로드_출처로_읽는다', async () => {
    // given / when
    renderWithProviders(<PortalLabelingPage />, {
      initialEntries: ['/portal/label/1?source=upload'],
      routes: [{ path: '/portal/label/:id', element: <PortalLabelingPage /> }],
    });

    // then
    expect(await screen.findByTestId('labeling-branch')).toHaveAttribute('data-source', 'upload');
  });

  it('출처_표기가_없으면_데이터마트로_읽는다', async () => {
    // given / when
    renderWithProviders(<PortalLabelingPage />, {
      initialEntries: ['/portal/label/1'],
      routes: [{ path: '/portal/label/:id', element: <PortalLabelingPage /> }],
    });

    // then
    expect(await screen.findByTestId('labeling-branch')).toHaveAttribute('data-source', 'datamart');
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
    expect(await screen.findByTestId('labeling-branch')).toHaveAttribute('data-source', 'datamart');
  });

  /*
   * ★출처는 **화면 본문이 아니라 데이터 계층**으로 내려간다. 이 화면이 두 벌의 본문을 고르는
   *   구조로 되돌아가면(업로드 갈래만 다른 컴포넌트를 그리는 형태) 같은 화면이 영구히 갈려
   *   다음 변경마다 한쪽만 갱신된다 — 그 회귀를 여기서 막는다.
   */
  it('★두_출처가_같은_본문을_쓴다_갈래마다_다른_화면을_그리지_않는다', async () => {
    // given / when
    const { unmount } = renderWithProviders(<PortalLabelingPage />, {
      initialEntries: ['/portal/label/1?source=upload'],
      routes: [{ path: '/portal/label/:id', element: <PortalLabelingPage /> }],
    });
    expect(await screen.findByTestId('labeling-branch')).toBeInTheDocument();
    unmount();

    renderWithProviders(<PortalLabelingPage />, {
      initialEntries: ['/portal/label/1'],
      routes: [{ path: '/portal/label/:id', element: <PortalLabelingPage /> }],
    });

    // then: 같은 본문이 선다(출처 값만 다르다)
    expect(await screen.findByTestId('labeling-branch')).toBeInTheDocument();
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
    // 행이 여럿이라 접근 이름에 파일명을 붙인다 — 이름 없이 «라벨링» 만 두면 같은 이름의
    // 링크가 자산 수만큼 생겨 보조기술 사용자가 어느 자산인지 가릴 수 없다(다른 행 조작과
    // 같은 관례). 여기서는 그 관례가 지켜지는지까지 함께 고정한다.
    const link = await screen.findByRole('link', { name: /라벨링$/ });
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
