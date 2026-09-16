/**
 * 포털 업로드 목록 — **표 구조 회귀 가드**(DS-002 리디자인으로 새로 생긴 계약).
 *
 * 구 화면은 카드를 세로로 쌓았고, 지금은 열이 정해진 표다. 표로 바꾼 이유는 자산이 쌓일수록
 * «크기·프레임·올린 일시·만료» 를 자산끼리 **비교**하게 되는데 카드 목록은 같은 값이 행마다 다른
 * 자리에 있어 눈으로 훑을 수 없기 때문이다.
 *
 * 여기서 고정하는 것은 **구조와 문구**이지 모양이 아니다. 클래스는 단언하지 않는다.
 *
 * @design SCREEN-033
 * @design DS-002
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalUpload } from '@/features/portal/uploads/types';

import { PortalUploadPage } from '../PortalUploadPage';

const usePortalUploadsMock = vi.fn();
const useDeleteUploadMock = vi.fn();

vi.mock('@/features/portal/uploads/hooks/usePortalUploads', () => ({
  usePortalUploads: (params: unknown) => usePortalUploadsMock(params),
}));
vi.mock('@/features/portal/uploads/hooks/useDeleteUpload', () => ({
  useDeleteUpload: () => useDeleteUploadMock(),
}));

function up(over: Partial<PortalUpload> = {}): PortalUpload {
  return {
    uldSn: 1,
    uldTypeCd: 'VIDEO',
    orgnlFileNm: 'crossroad-0812-1430.mp4',
    fileSz: 1_331_439_861, // 1.24 GB
    mimeTypeNm: 'video/mp4',
    uldSttsCd: 'READY',
    frmeCnt: 240,
    frmeSn: 10,
    regDt: '2026-08-12T15:02:00',
    expiresAt: '2026-09-11T00:00:00',
    ...over,
  };
}

function mountWith(list: PortalUpload[]) {
  usePortalUploadsMock.mockReturnValue({
    data: { content: list, totalElements: list.length, totalPages: 1, number: 0, size: 20 },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  });
  useDeleteUploadMock.mockReturnValue({ deleteAsync: vi.fn(), isPending: false, error: null });
  renderWithProviders(<PortalUploadPage />, { initialEntries: ['/portal/uploads'] });
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('포털 업로드 목록 — 카드 구조', () => {
  /**
   * ★★ **표가 아니라 행 카드다 (2026-09-08 반전).** 여덟 열이 요구하는 최소 폭이 본문 최대
   *    폭(1,200px)을 넘어 파일명·상태 부제가 반드시 접히거나 잘렸다. 시안(SD-026)도 이 목록을
   *    **행**으로 그렸다 — 파일명·크기·상태 표식이 `flex-wrap` 으로 흐르는 짜임이다.
   *    표는 그 시안에서 이탈한 형태였고, 이 단언이 되돌아가는 것을 막는다.
   */
  it('★표가_아니라_행_카드_목록이다', () => {
    mountWith([up()]);
    expect(screen.queryByRole('table')).toBeNull();
    const list = screen.getByRole('list', { name: '업로드 자산 목록' });
    // ⚠ 2026-09-16 — 줄 카드가 **제 안에 목록을 품는다**(일시 줄 · 자산 정보 칩). 그래서
    //   `getAllByRole('listitem')` 은 안쪽 항목까지 함께 센다 — 구 기대값 「1」은 폐기하고
    //   **바로 아래 자식**을 센다(자산 한 건 = 카드 한 장이라는 축은 그대로다).
    expect(list.children).toHaveLength(1);
    expect(screen.getByTestId('portal-upload-item-1')).toBe(list.children[0]);
  });

  /**
   * ★★ **어디에도 말줄임을 두지 않는다.** 시안이 이 화면에서 명시적으로 거부한다 —
   *    *"잘리는 꼬리는 확장자다 … 왜 거부됐는지 화면에서 사라진다"*. `title` 보완도 쓰지 않는다
   *    (*"터치 환경이라 hover 툴팁이 뜨지 않고, 게시본 정리기가 지우는 속성 계열"*).
   */
  it('★긴_파일명을_자르지_않고_확장자까지_보인다', () => {
    const longName = 'YTDown_YouTube_Media_LH9blaB9pjg_001_1080p_아주긴이름.mp4';
    mountWith([up({ orgnlFileNm: longName })]);
    const card = screen.getByTestId('portal-upload-item-1');
    expect(card).toHaveTextContent(longName);
    expect(card.innerHTML).not.toContain('truncate');
    expect(card.querySelector('[title]')).toBeNull();
  });

  /**
   * ★ **왼쪽 장식을 두지 않는다** (2026-09-08 사용자 지적: *"ai 스러운 왼쪽 스트립 빼라"*).
   *   상태를 알리는 좌측 색 띠는 배지·실패 사유가 이미 나르는 정보였고, 갈래 아이콘 타일은
   *   이 목록의 갈래가 한 종류뿐이라 모든 줄에 같은 그림이 반복될 뿐이었다.
   */
  it('★상태를_알리는_좌측_색_띠를_두지_않는다', () => {
    mountWith([
      up({ uldSttsCd: 'PROCESSING', frmeCnt: null }),
      up({ uldSn: 2, uldSttsCd: 'FAILED', frmeCnt: null }),
    ]);
    for (const sn of [1, 2]) {
      expect(screen.getByTestId(`portal-upload-item-${sn}`).className).not.toContain('border-l-');
    }
  });

  /*
   * 영상은 최대 5GB 다. MB 에서 멈추면 `1269.7 MB` 처럼 자릿수가 길어져 자산끼리 비교가 안 된다.
   */
  it('기가바이트_단위까지_올려_표기한다', () => {
    mountWith([up({ fileSz: 1_331_439_861 })]);
    expect(screen.getByText('1.24 GB')).toBeInTheDocument();
  });

  it('올린_일시를_분까지_보여준다', () => {
    mountWith([up({ regDt: '2026-08-12T15:02:33' })]);
    // 초는 목록에서 의미가 없고 자리폭만 흔든다.
    expect(screen.getByText('2026-08-12 15:02')).toBeInTheDocument();
  });

  /* 사실 조각은 「이름 값」 칩으로 흩는다 — 값을 그대로 노출하지 않고 한글로 옮긴다. */
  it('자산_유형을_한글_칩으로_보여준다', () => {
    mountWith([up({ uldTypeCd: 'VIDEO' })]);
    const card = within(screen.getByTestId('portal-upload-item-1'));
    expect(card.getByText('유형')).toBeInTheDocument();
    expect(card.getByText('영상')).toBeInTheDocument();
  });

  /**
   * ★ 표에서는 열이 고정이라 값이 비면 «열이 밀렸나» 로 읽혀 대시로 자리를 지켰다. 카드에는
   *   열이 없으므로 **칩 자체를 두지 않는 것**이 옳다 — 없는 값을 위해 자리를 지어내지 않는다.
   */
  it('★프레임_수가_아직_없으면_그_칩을_두지_않는다', () => {
    mountWith([up({ uldSttsCd: 'PROCESSING', frmeCnt: null })]);
    const card = within(screen.getByTestId('portal-upload-item-1'));
    expect(card.queryByText('프레임')).toBeNull();
    expect(card.queryByText('—')).toBeNull();
  });
});

describe('포털 업로드 목록 — 처리 중 행', () => {
  /*
   * ★ 비활성 버튼은 마우스 이벤트를 받지 않으므로 사유를 그 버튼에 걸 수 없다. 감싸는 요소가
   *   설명을 띄우고, 마크업에 실려 있어야(`role="tooltip"` + `aria-describedby`) 보조기술이 읽는다.
   *   ⚠ 이 단언이 없으면 «비활성이니 굳이» 로 사유가 사라지고, 사용자는 왜 못 지우는지 모른다.
   */
  it('★삭제가_막힌_이유를_마크업에_남긴다', () => {
    mountWith([up({ uldSttsCd: 'PROCESSING', frmeCnt: null, expiresAt: null })]);
    const del = screen.getByRole('button', { name: 'crossroad-0812-1430.mp4 삭제' });
    expect(del).toHaveAccessibleDescription(/프레임을 뽑는 중에는 지울 수 없습니다/);
  });

  /*
   * ⚠ 2026-09-16 — **속성으로 잠그지 않는다**(`disabled` 미사용). WCAG 2.1.1 — native `disabled`
   *   는 Tab 순서에서 빠져 위 시험이 지키려는 **사유 자체에 닿을 길이 사라진다**(초점이 안 가면
   *   스크린리더가 그 설명을 읽을 계기가 없다). 구 기대값 `toBeDisabled()` 는 폐기다.
   *   대신 **잠김이 실제로 막는지를 눌러서 확인한다** — 속성만 보면 «표시만 잠긴 버튼» 이
   *   통과하므로, 여기가 그 구멍을 메우는 자리다.
   */
  it('처리_중_자산의_삭제는_잠겨_있고_눌러도_열리지_않는다', async () => {
    const user = userEvent.setup();
    mountWith([up({ uldSttsCd: 'PROCESSING', frmeCnt: null, expiresAt: null })]);
    const del = screen.getByRole('button', { name: 'crossroad-0812-1430.mp4 삭제' });

    expect(del).toHaveAttribute('aria-disabled', 'true');

    await user.click(del);

    // 확인 창이 뜨지 않는다 — 잠김이 표시가 아니라 실제 차단이다.
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  /*
   * ⚠ 2026-09-16 — 삭제가 아이콘 버튼에서 **글자 버튼**이 되면서 말풍선이 사라졌다. 구 기대값
   *   `toHaveAccessibleDescription('삭제')` 는 폐기다 — 그 설명은 아이콘만 있던 시절 말풍선이
   *   남기던 것이고, 지금은 버튼 **자신이** 「삭제」라고 말한다(설명을 또 두면 같은 말이 두 번).
   *   지키는 축은 그대로다: 막을 사유가 없으면 **사유 없이 눌린다.**
   */
  it('준비_완료_자산의_삭제는_사유_없이_눌린다', () => {
    mountWith([up()]);
    const del = screen.getByRole('button', { name: /삭제$/ });
    expect(del).toHaveTextContent('삭제');
    expect(del).not.toHaveAttribute('aria-disabled');
    expect(del).toHaveAccessibleDescription('');
    expect(del).toBeEnabled();
  });
});

describe('포털 업로드 목록 — 상태별 조작 노출', () => {
  /*
   * 노출 규칙은 세 조작이 같다: **그 자산에서 할 수 없는 액션은 비활성으로 두지 않고 아예
   * 노출하지 않는다.** 비활성으로 두면 눌러 봐야 거절되는 자리가 되어 회복 경로를 잘못 안내한다.
   */
  it('마킹_대기_자산에는_마킹만_있고_라벨링_증강은_없다', () => {
    mountWith([up({ uldSttsCd: 'UPLOADED', frmeCnt: null })]);
    expect(screen.getByRole('link', { name: /마킹$/ })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /라벨링$/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /AI 증강 요청$/ })).toBeNull();
  });

  it('준비_완료_자산에는_라벨링과_증강이_있고_마킹은_없다', () => {
    mountWith([up()]);
    expect(screen.getByRole('link', { name: /라벨링$/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /AI 증강 요청$/ })).toBeInTheDocument();
    // 재마킹은 제공하지 않는다 — 두면 눌러 봐야 거절되는 자리가 된다.
    expect(screen.queryByRole('link', { name: /마킹$/ })).toBeNull();
  });

  it('실패_자산에는_사유가_행에_붙는다', () => {
    mountWith([
      up({ uldSttsCd: 'FAILED', frmeCnt: null, failRsnCn: '영상을 읽지 못했습니다' }),
    ]);
    expect(screen.getByText('영상을 읽지 못했습니다')).toBeInTheDocument();
  });
});
