/**
 * 포털 증강 화면 — **화면 짜임과 말투**의 회귀 가드(DS-002 리디자인으로 새로 생긴 계약).
 *
 * <h3>이 파일이 고정하는 것</h3>
 * <ul>
 *   <li>★ **자기 페이지 제목(`h1`)을 두지 않는다** — Host 머리 영역이 서비스 이름을, 본문 상단
 *       이동 탭의 활성 항목이 화면 이름을 이미 말한다. 이 화면에는 `증강` 이라는 제목이 탭과
 *       나란히 두 번 떠 있었다.</li>
 *   <li>★ 「조회 실패」와 「실제로 0건」이 **다른 모습**이고, 실패 쪽에는 **다시 시도할 자리**가 있다.
 *       둘을 같은 모습으로 두면 서버 오류를 요청이 사라진 것으로 오해해 같은 요청을 다시 건다.</li>
 *   <li>★ 빈 상태에는 **갈 곳이 있다** — 증강을 거는 자리가 이 배포본 안(내 업로드)이기 때문이다.
 *       형제 화면(내 작업)은 갈 곳이 바깥이라 조작을 두지 않는다. **두 화면의 차이는 의도다.**</li>
 *   <li>대기 안내가 **부정으로 시작하지 않는다** — 구 문구는 «요청한 즉시 결과가 나오지
 *       않습니다» 라 무엇이 잘못된 것처럼 읽혔다.</li>
 * </ul>
 *
 * 모양(색·여백)은 단언하지 않는다 — 채널이 산출 시점에 값을 정한다.
 *
 * @design SCREEN-044
 * @design DS-002
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, screen, within } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalAugmentSummary } from '@/features/portal/augments/types';

import { PortalAugmentPage } from '../PortalAugmentPage';

const usePortalAugmentsMock = vi.fn();
const usePortalAugmentMock = vi.fn();

vi.mock('@/features/portal/augments/hooks/usePortalAugments', () => ({
  usePortalAugments: (params: unknown) => usePortalAugmentsMock(params),
}));
vi.mock('@/features/portal/augments/hooks/usePortalAugment', () => ({
  usePortalAugment: (augSn: unknown) => usePortalAugmentMock(augSn),
}));
vi.mock('@/features/portal/uploads/hooks/useUploadDetail', () => ({
  useUploadDetail: () => ({ data: undefined, isLoading: false, isError: false }),
}));
vi.mock('@/features/portal/uploads/hooks/useUploadFrameImage', () => ({
  useUploadFrameImage: () => ({ url: null, loading: false, error: null }),
}));

function row(over: Partial<PortalAugmentSummary> = {}): PortalAugmentSummary {
  return {
    augSn: 9001,
    uldSn: 501,
    augSttsCd: 'ACCEPTED',
    failRsnCn: null,
    orgnlFileNm: 'street.mp4',
    requestedAt: '2026-09-01T10:00:00',
    resultReady: false,
    resultArrivedAt: null,
    generationCondition: { weather: 'RAIN' },
    ...over,
  };
}

type ListState = { isLoading?: boolean; isError?: boolean };

function mount(content: PortalAugmentSummary[], state: ListState = {}) {
  const { isLoading = false, isError = false } = state;
  usePortalAugmentsMock.mockReturnValue({
    data:
      isLoading || isError
        ? undefined
        : { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 },
    isLoading,
    isError,
    refetch: vi.fn(),
  });
  usePortalAugmentMock.mockReturnValue({
    data: undefined,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  });
  renderWithProviders(<PortalAugmentPage />);
}

afterEach(() => {
  usePortalAugmentsMock.mockReset();
  usePortalAugmentMock.mockReset();
  cleanup();
});

describe('포털 증강 — 화면 짜임', () => {
  /**
   * ★★ 이 화면은 Host 화면 **안**에서 실행된다. 서비스 이름은 Host 머리 영역이, 화면 이름은
   *    이동 탭의 활성 항목이 이미 말한다 — `h1` 을 두면 같은 말이 두 번 뜬다.
   *    ⚠ 되살리지 말 것: 형제 화면 시안(SD-024)이 같은 이유로 제목 밴드를 걷어냈고 이 화면만
   *      남아 있었다.
   */
  it('★자기_페이지_제목을_두지_않는다_Host_와_이동_탭이_이미_말한다', () => {
    mount([row()]);
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull();
    expect(screen.getByRole('heading', { level: 2, name: '증강 요청 현황' })).toBeInTheDocument();
  });

  /** 대기 구간을 숨기지 않되 부정으로 시작하지 않는다 — 사실은 같고 말투만 다르다. */
  it('대기_안내가_사실을_알리되_부정으로_시작하지_않는다', () => {
    mount([row()]);
    const lead = screen.getByText(
      '증강은 시간이 걸립니다. 결과가 도착하면 목록의 상태가 바뀝니다.',
    );
    expect(lead).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('요청한 즉시 결과가 나오지 않습니다');
  });

  it('요청이_있으면_건수와_영상_고르러_가는_길이_목록_머리에_함께_선다', () => {
    mount([row(), row({ augSn: 9002 })]);
    /*
     * ⚠ 2026-09-16 — 건수가 **제목 옆에서 목록 바로 위로** 내려오고 표기도 「2건」 → 「총 2건」이
     *   됐다(포털 공용 건수 줄). 구 기대값 `getByText('2건')` 은 폐기다 — 「총」과 숫자가 서로
     *   다른 요소에 담겨 그 글자만 가진 요소가 더는 없다.
     *   건수 줄은 조건을 좁혔을 때 그 사실이 **여기서만** 바뀌므로 `role="status"` 로 읽어 준다 —
     *   그 성질까지 함께 고정한다(구 표기에는 없던 축이다).
     */
    expect(screen.getByRole('status')).toHaveTextContent('총 2건');
    expect(screen.getByRole('link', { name: '증강할 영상 고르러 가기' })).toBeInTheDocument();
  });
});

describe('포털 증강 — 조회 실패와 0건은 다른 모습이다', () => {
  /**
   * ★ 서버 오류를 「요청이 사라졌다」로 오해하면 사용자가 같은 요청을 다시 건다. 그래서 실패는
   *   빈 상태보다 **앞에서** 받고, 사라진 것이 아니라는 사실과 **다시 시도할 자리**를 함께 준다.
   */
  it('★조회_실패는_사라진_것이_아님을_말하고_다시_시도할_자리를_준다', () => {
    mount([], { isError: true });
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('요청 현황을 불러올 수 없습니다');
    expect(alert).toHaveTextContent('낸 요청이 사라진 것은 아닙니다');
    expect(within(alert).getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    // 0건 안내와 섞이지 않는다.
    expect(screen.queryByText('아직 요청한 증강이 없습니다.')).toBeNull();
  });

  /**
   * ★★ 빈 상태에 **갈 곳을 둔다** — 증강을 거는 자리가 이 배포본 안(내 업로드)이라 갈 수 있다.
   *    형제 화면(내 작업)은 갈 곳이 바깥(Host 화면)이라 조작을 두지 않는다. 차이는 의도다.
   */
  it('★0건이면_사실과_함께_증강을_걸_수_있는_자리로_보낸다', () => {
    mount([]);
    const empty = screen.getByRole('status');
    expect(empty).toHaveTextContent('아직 요청한 증강이 없습니다.');
    expect(empty).toHaveTextContent('내 업로드에서 준비가 끝난 영상을 골라');
    expect(within(empty).getByRole('link', { name: '증강할 영상 고르러 가기' })).toHaveAttribute(
      'href',
      '/portal/uploads',
    );
  });

  /**
   * 같은 접근 이름의 링크가 한 화면에 둘이면 보조기술 사용자가 어느 쪽인지 가릴 수 없다 —
   * 그래서 0건일 때는 머리 쪽 링크를 그리지 않는다.
   */
  it('0건일_때_같은_이름의_링크가_둘이_되지_않는다', () => {
    mount([]);
    expect(screen.getAllByRole('link', { name: '증강할 영상 고르러 가기' })).toHaveLength(1);
  });

  it('불러오는_중임을_글로_알리고_목록이_올_자리를_지킨다', () => {
    mount([], { isLoading: true });
    /*
     * ⚠ 2026-09-16 — 구 이름의 「자리표시자」는 **줄 모양 골격**(skeleton)이었고, 지금은 빈 목록과
     *   같은 판 위에 도는 고리 하나가 선다. 자리를 지킨다는 뜻은 그대로이고 그리는 것이 바뀌었다.
     *   단언(글로 알린다)은 그때도 지금도 같은 축이라 그대로 둔다.
     */
    expect(screen.getByRole('status')).toHaveTextContent('요청 현황을 불러오고 있습니다.');
  });
});

describe('포털 증강 — 목록 카드', () => {
  /**
   * ★★ **표를 쓰지 않는 이유는 취향이 아니라 폭이다 (2026-09-08 반전).**
   *    한 행이 요구하는 최소 폭이 `일시 144 + 파일명 400 + 생성 조건 444 + 상태 534 + 조작 114
   *    ≈ 1,636px` 인데 본문 최대 폭은 **1,200px** 이라 436px 이 구조적으로 모자란다. 열 폭을
   *    어떻게 나눠도 어느 칸이든 반드시 접히거나 잘렸고, 실제로 생성 조건 칸이 최소 폭까지 눌려
   *    **한 글자씩 세로로 흘러내렸다.**
   *    ⚠ 표로 되돌리지 말 것 — 되돌리면 같은 폭 부족이 그대로 돌아온다.
   */
  it('★표가_아니라_행_카드_목록이다', () => {
    mount([row()]);
    expect(screen.queryByRole('table')).toBeNull();
    const list = screen.getByRole('list', { name: '증강 요청 목록' });
    /*
     * ⚠ 2026-09-16 — 구 기대값 `within(list).getAllByRole('listitem')` 은 폐기다. 줄 카드 안에
     *   **생성 조건 칩과 일시가 각각 제 목록으로** 들어와, 그렇게 세면 카드 한 장이 세 건으로
     *   잡힌다(칩 · 일시 · 카드). 세어야 하는 것은 「요청 한 건 = 카드 한 장」이므로 목록의
     *   **바로 아래 자식**만 센다.
     */
    expect(list.querySelectorAll(':scope > li')).toHaveLength(1);
  });

  /**
   * ★★ **어디에도 말줄임을 두지 않는다.** 말줄임은 폭 부족분을 이용자에게 떠넘긴 것이고,
   *    실패 사유를 읽으려면 매번 「결과 확인」을 눌러야 했다. 시안(SD-026)은 이 화면 계열에서
   *    말줄임과 `title` 보완을 **둘 다 거부**한다 — 터치 환경에서 말풍선이 뜨지 않고, 게시본
   *    정리기가 그 속성을 지운다.
   *    ⚠ 이 단언을 지우면 폭이 모자란 날 누군가 다시 `truncate` 를 건다.
   */
  it('★긴_값을_자르지_않고_전문을_보인다', () => {
    const longName = 'YTDown_YouTube_Media_LH9blaB9pjg_001_1080p_아주긴이름.mp4';
    const longReason = '증강 생성에 실패했습니다. 생성 조건을 바꾸어 다시 요청해 주세요.';
    mount([
      row({
        orgnlFileNm: longName,
        resultReady: false,
        failRsnCn: longReason,
        generationCondition: {
          time: 'DUSK',
          season: 'SUMMER',
          weather: 'SNOW',
          terrain: 'UNDERPASS',
          severity: 'LOW',
        },
      }),
    ]);
    const card = screen.getByTestId('portal-augment-row-9001');

    expect(card).toHaveTextContent(longName);
    expect(card).toHaveTextContent(longReason);
    expect(card.innerHTML).not.toContain('truncate');
    expect(card.querySelector('[title]')).toBeNull();
  });

  /**
   * ★ 생성 조건은 **칩으로 흩는다** — 다섯을 한 덩이 문자열로 이으면 폭이 모자랄 때 통째로
   *   잘려 어느 값이 사라졌는지조차 알 수 없다. 칩은 줄바꿈이 자연스럽고 따로 읽힌다.
   */
  it('★생성_조건_다섯이_각각_읽히는_칩으로_선다', () => {
    mount([
      row({
        generationCondition: {
          time: 'DUSK',
          season: 'SUMMER',
          weather: 'SNOW',
          terrain: 'UNDERPASS',
          severity: 'LOW',
        },
      }),
    ]);
    const card = within(screen.getByTestId('portal-augment-row-9001'));
    const names = card.getAllByText(/^(시간대|계절|날씨|지형|심각도)$/);
    expect(names.map((n) => n.textContent)).toEqual(['시간대', '계절', '날씨', '지형', '심각도']);
  });

  /**
   * 목록의 일시는 행끼리 비교하는 값이라 자리폭이 흔들리면 세로로 훑을 수 없다. 로케일 표기
   * (`2026. 9. 8. 오후 3:09:22`)는 오전/오후·한 자리 월이 섞여 그 성질을 잃고, 초는 목록에서
   * 의미가 없으면서 폭만 먹는다. 포털 목록 셋이 같은 모양을 쓴다.
   */
  it('요청_일시를_분까지_자리폭_고정으로_보인다', () => {
    mount([row({ requestedAt: '2026-09-08T15:09:22' })]);
    expect(
      within(screen.getByTestId('portal-augment-row-9001')).getByText(/2026-09-08 15:09/),
    ).toBeInTheDocument();
  });
});
