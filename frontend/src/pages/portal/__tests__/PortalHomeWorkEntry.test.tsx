// 포털 내 작업 — **이어서 작업 진입** 회귀 가드. @design SCREEN-028
//
// 이 파일이 고정하는 계약:
//  - ★ 진입 자리는 **출처마다 갈린다.** 데이터마트 행은 프레임 식별자로 열고, 업로드 행은 자산
//    식별자로 열며 프레임은 표기가 나른다. 한쪽 규약으로 합치면 업로드 행이 있지도 않은 데이터마트
//    프레임을 조회하러 가고, 라우터 가드가 정상적으로 막아 **인가 결함처럼 보이는** 증상이 된다.
//  - ★★ 진입 자리가 빈 행(`entrySrcSn === null`)은 **목록에 남되** 이어서 작업만 누를 수 없고
//    **누를 수 없다는 사실이 드러난다.** 목록에서 빼면 삭제 대상인 작업물이 화면에서 사라진다.
//  - ★★★ **그 자리가 비는 사유는 둘인데 창구는 값 하나로만 말한다** — ①열 프레임이 없다(업로드인데
//    마킹·추출 전) ②진입이 허용되지 않는다(데이터마트 영상이 노출 조건을 잃었다). 응답이 둘을
//    구분해 주지 않으므로 화면은 **두 사유 모두에 참인 공통 문구**를 쓴다. 사유 하나를 단정하면
//    ②에서 **프레임이 멀쩡히 있는데 없다고** 말하게 되어 사용자가 엉뚱한 회복 경로로 간다.
//    ⚠ 아래 두 갈래를 **각각** 세우고 **같은 문구로 덮이는지**까지 고정한다 — 한 갈래만 세우면
//      문구가 다른 갈래에서 거짓이 되어도 통과한다(실제로 그 상태였다).
//  - ★★ **진입 가부와 내려받기·만료 표기는 다른 축이라 함께 막히지 않는다.**
//  - 이어서 작업이 여는 자리는 **첫 프레임 고정이 아니다** — 서버가 정한 이어쓰기 지점을 그대로 쓴다.

import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalUserWork } from '@/features/portal/api';

import { PortalHomePage } from '../PortalHomePage';

const useUserWorksMock = vi.fn();
vi.mock('@/features/portal/hooks/useUserWorks', () => ({
  useUserWorks: (params: { page?: number; size?: number }) => useUserWorksMock(params),
}));

function work(over: Partial<PortalUserWork> = {}): PortalUserWork {
  return {
    rawSn: 10,
    assetSource: 'DATAMART',
    videoName: 'CLIP-10',
    labelCount: 3,
    lastSavedAt: '2026-06-01T10:00:00',
    entrySrcSn: 100,
    expiresOn: '2026-06-08',
    ...over,
  };
}

function mockWorks(content: PortalUserWork[]) {
  useUserWorksMock.mockReturnValue({
    data: { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 },
    isLoading: false,
    isError: false,
  });
}

function renderHome() {
  return renderWithProviders(<PortalHomePage />, {
    initialEntries: ['/portal'],
    routes: [
      { path: '/portal', element: <PortalHomePage /> },
      { path: '/portal/label/:id', element: <div data-testid="label-route">labeling</div> },
    ],
  });
}

afterEach(() => {
  useUserWorksMock.mockReset();
});

describe('포털 내 작업 — 이어서 작업 진입 자리', () => {
  it('★데이터마트_행은_프레임_식별자로_연다_출처_표기가_붙지_않는다', () => {
    // given
    mockWorks([work({ rawSn: 10, assetSource: 'DATAMART', entrySrcSn: 100 })]);

    // when
    renderHome();

    // then
    const link = screen.getByTestId('portal-work-continue-10');
    expect(link).toHaveAttribute('href', '/portal/label/100');
    // then: 업로드 축 표기가 새어 붙지 않는다(붙으면 화면이 업로드 창구로 조회하러 간다)
    expect(link.getAttribute('href')).not.toContain('source=upload');
  });

  it('★업로드_행은_자산_식별자로_열고_프레임은_표기가_나른다', () => {
    // given: 업로드 축에서 rawSn 이 곧 자산 식별자다
    mockWorks([
      work({ rawSn: 77, assetSource: 'PORTAL_UPLOAD', entrySrcSn: 900, lastSavedAt: null, labelCount: 0 }),
    ]);

    // when
    renderHome();

    // then: 자산으로 열고(`/portal/label/77`) 프레임은 표기로 나른다
    const href = screen.getByTestId('portal-work-continue-77').getAttribute('href') ?? '';
    expect(href.startsWith('/portal/label/77?')).toBe(true);
    expect(href).toContain('source=upload');
    expect(href).toContain('frame=900');
    // then: 프레임 식별자로 열지 않는다 — 그러면 있지도 않은 데이터마트 프레임을 조회한다
    expect(href.startsWith('/portal/label/900')).toBe(false);
  });

  /*
   * ★ 이어쓰기 — 서버가 준 지점을 그대로 연다. 구 동작은 언제나 **첫 프레임**이라 작업을 멈춘
   *   자리로 돌아갈 수 없었다. 첫 프레임과 다른 값을 픽스처에 실어 그 회귀를 잡는다.
   */
  it('★이어서_작업은_서버가_정한_지점을_연다_첫_프레임_고정이_아니다', async () => {
    // given: 마지막으로 저장한 프레임(482)이 실려 온다
    const user = userEvent.setup();
    mockWorks([work({ rawSn: 10, entrySrcSn: 482 })]);
    renderHome();

    // when
    await user.click(screen.getByTestId('portal-work-continue-10'));

    // then: 그 자리로 이동한다
    expect(screen.getByTestId('label-route')).toBeInTheDocument();
  });
});

describe('포털 내 작업 — 들어갈 수 없는 행', () => {
  it('★진입_대상_프레임이_없으면_이어서_작업을_누를_수_없고_사유가_드러난다', async () => {
    // given: 마킹·추출 전이라 열 프레임이 하나도 없는 업로드 행
    const user = userEvent.setup();
    mockWorks([
      work({
        rawSn: 88,
        assetSource: 'PORTAL_UPLOAD',
        entrySrcSn: null,
        lastSavedAt: null,
        labelCount: 0,
      }),
    ]);
    renderHome();

    // then(존재): 행은 그대로 목록에 있다 — 빼면 삭제 대상인 작업물이 화면에서 사라진다
    expect(screen.getByTestId('portal-work-row-88')).toBeInTheDocument();

    // then: 이동 수단(링크)이 아니라 누를 수 없는 조작이다
    const control = screen.getByTestId('portal-work-continue-88');
    expect(control).toHaveAttribute('aria-disabled', 'true');
    expect(control).not.toHaveAttribute('href');
    // WCAG 2.1.1 — native disabled 는 Tab 순서에서 빠져 사유를 읽을 길이 사라진다
    expect(control).not.toBeDisabled();

    // then(존재): 누를 수 없다는 사실이 눈으로도 보조기술로도 읽힌다
    //   ⚠ 원인을 단정하지 않는다 — 이 갈래는 실제로 프레임이 없지만, 같은 값이 오는 다른 갈래는
    //     프레임이 멀쩡히 있다. 화면은 그 둘을 구분할 근거가 없다.
    expect(screen.getByText('지금은 이어서 작업할 수 없습니다.')).toBeInTheDocument();
    expect(control).toHaveAccessibleDescription('지금은 이어서 작업할 수 없습니다.');
    expect(screen.queryByText(/프레임이 없/)).toBeNull();

    // when: 눌러도 화면을 떠나지 않는다
    await user.click(control);

    // then
    expect(screen.queryByTestId('label-route')).toBeNull();
  });

  /*
   * ★★ 진입 가부와 내려받기·만료는 **다른 축**이다. 함께 막으면 내려받을 것이 있는데도 막히고,
   *    만료를 숨기면 그 행을 목록에 남긴 이유 자체가 사라진다.
   */
  it('★들어갈_수_없는_행이어도_만료_예정일과_내려받기는_그대로_동작한다', () => {
    // given: 프레임은 없는데 저작물(메타만)은 있는 행
    mockWorks([
      work({
        rawSn: 89,
        assetSource: 'PORTAL_UPLOAD',
        entrySrcSn: null,
        labelCount: 0,
        lastSavedAt: '2026-06-03T11:00:00',
        expiresOn: '2026-06-10',
      }),
    ]);

    // when
    renderHome();

    // then: 이어서 작업만 막힌다
    expect(screen.getByTestId('portal-work-continue-89')).toHaveAttribute('aria-disabled', 'true');
    // then: 만료 예정일은 그대로 보인다 — 그것이 이 행을 목록에 남기는 이유다
    expect(screen.getByTestId('portal-work-expiry-89')).toHaveTextContent('만료: 2026-06-10');
    // then: 내려받기는 함께 막히지 않는다
    expect(screen.getByTestId('portal-work-download-89')).toBeEnabled();
  });

  /*
   * ★★ 두 번째 갈래 — **프레임은 있는데 진입이 허용되지 않는** 데이터마트 행.
   *    노출 조건을 잃은 영상이 여기 해당한다. 값(`entrySrcSn === null`)은 첫 갈래와 같지만
   *    **사실관계가 정반대**라, 사유를 단정한 문구는 여기서 거짓말이 된다.
   */
  it('★진입이_허용되지_않는_데이터마트_행도_같은_자리에서_막힌다_프레임_유무를_단정하지_않는다', () => {
    // given: 저작 이력이 있는(=작업하던) 데이터마트 영상인데 진입 자리가 비어 왔다
    mockWorks([
      work({
        rawSn: 90,
        assetSource: 'DATAMART',
        entrySrcSn: null,
        labelCount: 4,
        lastSavedAt: '2026-06-04T08:00:00',
        expiresOn: '2026-06-11',
      }),
    ]);

    // when
    renderHome();

    // then(존재): 행은 남고 이어서 작업만 막힌다
    expect(screen.getByTestId('portal-work-row-90')).toBeInTheDocument();
    expect(screen.getByTestId('portal-work-continue-90')).toHaveAttribute('aria-disabled', 'true');
    // then(존재): 안내는 나오되 **프레임이 없다고 말하지 않는다** — 이 행은 프레임이 있다
    expect(screen.getByText('지금은 이어서 작업할 수 없습니다.')).toBeInTheDocument();
    // then(부재): 사유를 단정하는 표현이 없다
    expect(screen.queryByText(/프레임이 없|마킹|추출/)).toBeNull();
    // then: 다른 축은 함께 막히지 않는다
    expect(screen.getByTestId('portal-work-expiry-90')).toHaveTextContent('만료: 2026-06-11');
    expect(screen.getByTestId('portal-work-download-90')).toBeEnabled();
  });

  /*
   * ★★ 두 갈래가 **같은 문구**로 덮이는지 — 창구가 사유를 구분해 주지 않으므로 화면이 갈라 적으면
   *    그 구분은 화면이 지어낸 것이 된다.
   */
  it('★사유가_다른_두_갈래가_한_문구로_덮인다_화면이_사유를_지어내지_않는다', () => {
    // given: ①업로드·프레임 0건(사유 = 프레임 없음) ②데이터마트·진입 불가(사유 = 노출 조건 상실)
    mockWorks([
      work({
        rawSn: 91,
        assetSource: 'PORTAL_UPLOAD',
        entrySrcSn: null,
        labelCount: 0,
        lastSavedAt: null,
      }),
      work({
        rawSn: 92,
        assetSource: 'DATAMART',
        entrySrcSn: null,
        labelCount: 4,
        lastSavedAt: '2026-06-04T08:00:00',
      }),
    ]);

    // when
    renderHome();

    // then: 두 행이 **문자 그대로 같은** 안내를 단다
    const first = within(screen.getByTestId('portal-work-row-91'));
    const second = within(screen.getByTestId('portal-work-row-92'));
    const firstText = first.getByText(/이어서 작업할 수 없/).textContent;
    const secondText = second.getByText(/이어서 작업할 수 없/).textContent;
    expect(firstText).toBe('지금은 이어서 작업할 수 없습니다.');
    expect(secondText).toBe(firstText);
  });
});
