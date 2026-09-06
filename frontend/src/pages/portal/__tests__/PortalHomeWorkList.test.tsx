// 포털 진입 화면 — **내 저장 작업 목록**의 구성·모집단·빈 상태·페이징 회귀 가드. @design SCREEN-028
//
// 이 파일이 고정하는 계약:
//  - ★★ 목록은 **데이터마트 카탈로그가 아니다.** 소비하는 창구가 「내 작업」 창구 하나이며, 구
//    카탈로그 창구를 다시 부르면 같은 목록이 Host 와 두 번 보인다.
//  - ★★ **`labelCount` 가 0 인 행도 정상 행이다** — 목록에 그려지고 빈 상태로 대체되지 않는다.
//    라벨 없이 메타·이벤트 어노테이션만 고친 행, 아직 아무것도 저장하지 않은 업로드 행이 그렇다.
//  - 표는 사양이 정한 네 열(대상 영상·저장 시각·만료 예정일·작업)로 이루어진다.
//  - 저장 시각·만료 예정일은 값이 없으면 **자리를 비운다**(`-`·`없음` 을 지어내지 않는다).
//    ★ 저장 시각이 없는 행이 **목록 순서에서 끝으로 밀리지 않는다**(정렬은 서버가 하고 화면은
//      받은 순서를 그대로 그린다 — 화면이 다시 정렬하면 서버의 「자산이 생긴 시각으로 대신한다」가
//      무력해져 방금 올린 자산이 마지막 쪽으로 가라앉는다).
//  - 전체가 한 쪽에 들어오면 페이저를 그리지 않는다.

import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalUserWork } from '@/features/portal/api';

import { PortalHomePage } from '../PortalHomePage';

const useUserWorksMock = vi.fn();
vi.mock('@/features/portal/hooks/useUserWorks', () => ({
  useUserWorks: (params: { page?: number; size?: number }) => useUserWorksMock(params),
}));

/**
 * 구 카탈로그 창구가 **다시 배선되면** 즉시 드러나게 한다.
 *
 * ⚠ 그 조회 함수는 이번에 프론트에서 제거됐다(서버 창구 자체는 남아 있다 — 제거는 이번 범위가
 *   아니다). 그래서 이 모의는 «지금 안 부른다» 가 아니라 «다시 만들어 부르면 잡는다» 를 위한
 *   것이다 — 모듈 모의는 원본에 없는 이름도 얹으므로, 누군가 같은 이름으로 되살려 호출하면
 *   이 spy 가 그것을 집는다.
 */
const datamartCallSpy = vi.fn();
vi.mock('@/features/portal/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/portal/api')>();
  return {
    ...actual,
    listDatamartVideos: (...args: unknown[]) => {
      datamartCallSpy(...args);
      return Promise.resolve({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
    },
  };
});

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

function mockWorks(content: PortalUserWork[], over: Partial<{ isLoading: boolean; totalPages: number; totalElements: number }> = {}) {
  const { isLoading = false, totalPages = 1, totalElements = content.length } = over;
  useUserWorksMock.mockReturnValue({
    data: isLoading ? undefined : { content, totalElements, totalPages, number: 0, size: 20 },
    isLoading,
    isError: false,
  });
}

function renderHome(initialEntries = ['/portal']) {
  return renderWithProviders(<PortalHomePage />, {
    initialEntries,
    routes: [
      { path: '/portal', element: <PortalHomePage /> },
      { path: '/portal/label/:id', element: <div data-testid="label-route">labeling</div> },
    ],
  });
}

afterEach(() => {
  useUserWorksMock.mockReset();
  datamartCallSpy.mockReset();
});

describe('포털 내 작업 — 목록 구성', () => {
  it('★데이터마트_카탈로그_창구를_부르지_않는다', () => {
    // given: 내 작업 목록이 정상으로 내려온다
    mockWorks([work()]);

    // when
    renderHome();

    // then(존재): 내 작업 목록이 실제로 그려졌다 — 아무것도 안 그리고 «호출 0건» 만 단언하면
    //             그 단언은 공허하다(자리 자체가 없어도 통과한다)
    expect(screen.getByTestId('portal-work-table')).toBeInTheDocument();
    expect(screen.getByTestId('portal-work-row-10')).toBeInTheDocument();
    // then(부재): 그러면서 구 카탈로그 창구는 한 번도 불리지 않는다
    expect(datamartCallSpy).not.toHaveBeenCalled();
  });

  it('사양이_정한_네_열을_갖는다', () => {
    // given
    mockWorks([work()]);

    // when
    renderHome();

    // then
    const headers = screen.getAllByRole('columnheader').map((th) => th.textContent);
    expect(headers).toEqual(['대상 영상', '저장 시각', '만료 예정일', '작업']);
  });

  /*
   * ★★ 이 파일의 핵심 가드 — 라벨 건수 0 인 행을 빼거나 빈 상태로 대체하면 **보존기간 삭제
   *    대상인 작업물을 사용자가 볼 수조차 없다.**
   */
  it('★라벨_건수가_0인_행도_목록에_그려지고_빈_상태로_대체되지_않는다', () => {
    // given: 라벨은 없고 메타만 고친 데이터마트 행 + 아직 아무것도 저장하지 않은 업로드 행
    mockWorks([
      work({ rawSn: 21, videoName: 'META-ONLY', labelCount: 0, lastSavedAt: '2026-06-02T09:00:00' }),
      work({
        rawSn: 22,
        videoName: 'FRESH-UPLOAD',
        assetSource: 'PORTAL_UPLOAD',
        labelCount: 0,
        lastSavedAt: null,
        entrySrcSn: 500,
        expiresOn: '2026-06-09',
      }),
    ]);

    // when
    renderHome();

    // then(존재): 두 행 모두 실린다
    expect(screen.getByTestId('portal-work-row-21')).toBeInTheDocument();
    expect(screen.getByTestId('portal-work-row-22')).toBeInTheDocument();
    expect(screen.getByText('META-ONLY')).toBeInTheDocument();
    expect(screen.getByText('FRESH-UPLOAD')).toBeInTheDocument();
    // then(부재): 그러면서 빈 상태 안내로 대체되지 않는다
    expect(screen.queryByTestId('portal-work-empty')).toBeNull();
  });

  it('행이_하나도_없을_때만_빈_상태_안내가_나온다', () => {
    // given
    mockWorks([], { totalPages: 0, totalElements: 0 });

    // when
    renderHome();

    // then(존재)
    expect(screen.getByTestId('portal-work-empty')).toHaveTextContent('저장한 작업이 없습니다.');
    // then(부재): 표 자체가 없다
    expect(screen.queryByTestId('portal-work-table')).toBeNull();
  });

  it('행마다_자산_출처가_읽힌다_두_축이_한_목록에_섞이기_때문이다', () => {
    // given
    mockWorks([
      work({ rawSn: 31, assetSource: 'DATAMART' }),
      work({ rawSn: 32, assetSource: 'PORTAL_UPLOAD' }),
    ]);

    // when
    renderHome();

    // then: 색이 아니라 글자로 구분된다(색 단독 전달 금지)
    expect(screen.getByTestId('portal-work-source-31')).toHaveTextContent('데이터마트');
    expect(screen.getByTestId('portal-work-source-32')).toHaveTextContent('내 업로드');
  });
});

describe('포털 내 작업 — 저장 시각·만료 예정일 표기', () => {
  it('만료_예정일은_날짜까지만_적고_임박_강조를_두지_않는다', () => {
    // given
    mockWorks([work({ rawSn: 40, expiresOn: '2026-06-08' })]);

    // when
    renderHome();

    // then
    expect(screen.getByTestId('portal-work-expiry-40')).toHaveTextContent('만료: 2026-06-08');
    expect(screen.queryByText(/임박|곧 삭제|D-/)).toBeNull();
  });

  it('만료가_없으면_자리를_비운다_문구를_지어내지_않는다', () => {
    // given: 만료 판정이 서지 않는 행
    mockWorks([work({ rawSn: 41, expiresOn: null })]);

    // when
    renderHome();

    // then(존재): 그 칸 자체는 남아 정렬을 유지한다
    const cell = screen.getByTestId('portal-work-expiry-41');
    expect(cell).toBeInTheDocument();
    // then(부재): 그런데 내용이 없다
    expect(cell.textContent).toBe('');
    // then(부재): 그 행 어디에도 `-`·`없음` 같은 지어낸 문구가 없다
    //   ⚠ 화면 전체로 찾으면 열 이름('만료 예정일')과 안내 문구가 걸린다 — 판정 범위를 그 행으로 좁힌다.
    const row = screen.getByTestId('portal-work-row-41');
    expect(within(row).queryByText(/만료|없음/)).toBeNull();
  });

  /*
   * ★ 사양이 명시한 「표시 값과 정렬 값이 다르다」 — 정렬에는 자산이 생긴 시각이 대신 쓰이지만
   *   그 대체값을 표시로 끌어오지 않는다. 그리고 그 행이 **끝으로 밀리지 않는다.**
   *   ⚠ 대상 행을 목록 **한가운데**에 둔다 — 끝에 두면 화면이 자기 마음대로 다시 정렬해도 순서가
   *     같아 변이가 검출되지 않는다.
   */
  it('★저장_시각이_없는_행은_자리가_비지만_순서에서_끝으로_밀리지_않는다', () => {
    // given: 저장 이력이 없는 업로드 행을 가운데에 둔다(서버가 정한 순서)
    mockWorks([
      work({ rawSn: 51, videoName: 'FIRST' }),
      work({
        rawSn: 52,
        videoName: 'MIDDLE-NO-SAVE',
        assetSource: 'PORTAL_UPLOAD',
        labelCount: 0,
        lastSavedAt: null,
        entrySrcSn: 520,
      }),
      work({ rawSn: 53, videoName: 'LAST' }),
    ]);

    // when
    renderHome();

    // then(부재): 그 행의 저장 시각 칸은 비어 있다
    expect(screen.getByTestId('portal-work-saved-52').textContent).toBe('');
    // then(존재): 다른 행의 저장 시각은 그대로 적힌다 — 「전부 비어 있다」와 구분한다
    expect(screen.getByTestId('portal-work-saved-51').textContent).toContain('2026');
    // then(순서): 서버가 준 순서 그대로다 — 화면이 다시 정렬하지 않는다
    const rows = screen.getAllByTestId(/^portal-work-row-/);
    expect(rows.map((r) => r.getAttribute('data-testid'))).toEqual([
      'portal-work-row-51',
      'portal-work-row-52',
      'portal-work-row-53',
    ]);
  });
});

describe('포털 내 작업 — 페이징', () => {
  it('전체가_한_쪽에_들어오면_페이저를_그리지_않는다', () => {
    // given
    mockWorks([work()], { totalPages: 1 });

    // when
    renderHome();

    // then(존재): 목록은 그려졌다
    expect(screen.getByTestId('portal-work-table')).toBeInTheDocument();
    // then(부재): 옮길 쪽이 없으니 페이저는 없다
    expect(screen.queryByRole('navigation', { name: /페이지/ })).toBeNull();
  });

  it('여러_쪽이면_페이저로_옮기고_그_쪽을_창구에_실어_보낸다', async () => {
    // given
    const user = userEvent.setup();
    mockWorks([work()], { totalPages: 3, totalElements: 55 });
    renderHome();

    // then: 첫 진입은 첫 쪽
    expect(useUserWorksMock).toHaveBeenCalledWith(expect.objectContaining({ page: 0 }));

    // when
    await user.click(screen.getByRole('button', { name: '2페이지' }));

    // then
    await waitFor(() =>
      expect(useUserWorksMock).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })),
    );
  });

  it('주소의_page_를_읽어_그_쪽부터_조회한다_뒤로가기_북마크', () => {
    // given / when
    mockWorks([work()], { totalPages: 3 });
    renderHome(['/portal?page=2']);

    // then
    expect(useUserWorksMock).toHaveBeenCalledWith(expect.objectContaining({ page: 2 }));
  });

  it('주소의_page_가_비정상이면_첫_쪽으로_가둔다_400을_유발하지_않는다', () => {
    // given / when: 음수·비수치는 그대로 실어 보내면 조회가 400 이라 목록이 통째로 빈다
    mockWorks([work()], { totalPages: 3 });
    renderHome(['/portal?page=-5']);

    // then
    expect(useUserWorksMock).toHaveBeenCalledWith(expect.objectContaining({ page: 0 }));
  });
});

describe('포털 내 작업 — 셸 경계', () => {
  it('머리_영역과_좌측_주_메뉴를_그리지_않는다_Host_소유', () => {
    // given
    mockWorks([work()]);

    // when
    const { container } = renderHome();

    // then(존재): 본문(목록)은 그린다
    expect(within(container).getByTestId('portal-work-table')).toBeInTheDocument();
    // then(부재): 머리 영역·좌측 레일은 그리지 않는다 — 그리면 Host 화면과 부딪힌다
    expect(container.querySelector('header')).toBeNull();
    expect(container.querySelector('aside')).toBeNull();
  });
});
