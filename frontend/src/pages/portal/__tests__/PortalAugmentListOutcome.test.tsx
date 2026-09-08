// 회귀 가드 — 포털 증강 요청 현황 목록의 **3구분**. [@design SCREEN-044] [@design API-232]
//
// ★ 판정 차례가 계약이다: 도착 여부가 참이면 **결과 도착**, 거짓이면서 실패 사유가 비어 있으면
//   **결과 대기 중**, 거짓이면서 실패 사유가 차 있으면 **실패**. 어느 것도 아니면 **대기**다
//   (fail-closed — 모르는 상태를 도착으로 읽지 않는다).
// ★★ **목록 하나로 판정이 성립한다** — 실패를 가려내려고 행마다 단건 조회를 부르지 않는다.
// ★★★ **서버가 내려주는 요청 상태 값 자체로는 가르지 않는다** — 값역이 열려 있어 모르는 값이
//   오면 판정이 무너진다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalAugmentSummary } from '@/features/portal/augments/types';
import {
  AUGMENT_POLL_MS,
  augmentPollIntervalFor,
} from '@/features/portal/augments/hooks/usePortalAugments';

import { PortalAugmentPage } from '../PortalAugmentPage';

const usePortalAugmentsMock = vi.fn();
const usePortalAugmentMock = vi.fn();

vi.mock('@/features/portal/augments/hooks/usePortalAugments', async (importOriginal) => {
  // ⚠ 모듈을 통째로 모의하지 않는다 — 같은 파일이 내보내는 폴링 판정(`augmentPollIntervalFor`)
  //   까지 undefined 가 되어, 그 함수를 검증하는 케이스가 조용히 무의미해진다.
  const actual = await importOriginal<
    typeof import('@/features/portal/augments/hooks/usePortalAugments')
  >();
  return { ...actual, usePortalAugments: (params: unknown) => usePortalAugmentsMock(params) };
});
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
    generationCondition: { time: 'NIGHT', season: 'WINTER' },
    ...over,
  };
}

function mockList(rows: PortalAugmentSummary[]) {
  usePortalAugmentsMock.mockReturnValue({
    data: { content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 20 },
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  });
}

const cell = (augSn: number) => within(screen.getByTestId(`portal-augment-row-${augSn}`));

beforeEach(() => {
  usePortalAugmentsMock.mockReset();
  usePortalAugmentMock.mockReset();
  usePortalAugmentMock.mockReturnValue({
    data: undefined,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('증강 요청 현황 — 목록 하나로 셋을 가른다', () => {
  it('★도착_대기_실패가_목록_행만으로_갈린다', () => {
    mockList([
      row({ augSn: 1, resultReady: true, resultArrivedAt: '2026-09-02T10:00:00' }),
      row({ augSn: 2, resultReady: false, failRsnCn: null }),
      row({ augSn: 3, resultReady: false, failRsnCn: '증강 생성에 실패했습니다.' }),
    ]);

    renderWithProviders(<PortalAugmentPage />);

    expect(cell(1).getByText('결과 도착')).toBeInTheDocument();
    expect(cell(2).getByText('결과 대기 중')).toBeInTheDocument();
    expect(cell(3).getByText('실패')).toBeInTheDocument();
  });

  it('★실패를_가리려고_행마다_단건_조회를_부르지_않는다', () => {
    mockList([
      row({ augSn: 1, resultReady: false, failRsnCn: '실패했습니다.' }),
      row({ augSn: 2, resultReady: false, failRsnCn: '실패했습니다.' }),
      row({ augSn: 3, resultReady: false, failRsnCn: null }),
    ]);

    renderWithProviders(<PortalAugmentPage />);

    // 결과 확인을 누르기 전에는 단건 조회가 **활성화되지 않는다**(훅은 -1 로 불려 꺼져 있다).
    for (const call of usePortalAugmentMock.mock.calls) {
      expect(call[0]).toBeUndefined();
    }
  });

  it('★모르는_상태는_도착이_아니라_대기다_fail_closed', () => {
    // 서버가 처음 보는 상태 코드를 보내고 도착 여부·실패 사유가 모두 비어 있는 경우.
    mockList([row({ augSn: 4, augSttsCd: '처음보는상태', resultReady: false, failRsnCn: null })]);

    renderWithProviders(<PortalAugmentPage />);

    expect(cell(4).getByText('결과 대기 중')).toBeInTheDocument();
    expect(cell(4).queryByText('결과 도착')).toBeNull();
  });

  it('★도착이_실패보다_먼저다_도착한_요청에_옛_사유가_남아도_도착이다', () => {
    mockList([row({ augSn: 5, resultReady: true, failRsnCn: '옛 실패 사유' })]);

    renderWithProviders(<PortalAugmentPage />);

    expect(cell(5).getByText('결과 도착')).toBeInTheDocument();
    expect(cell(5).queryByTestId('portal-augment-fail-5')).toBeNull();
  });
});

describe('증강 요청 현황 — 실패 사유 표기', () => {
  /**
   * ★★ **사유는 전문을 보인다 — 자르지 않는다 (2026-09-08 반전).**
   *    구 처리는 «상태 배지와 같은 칸에 한 줄, 넘치면 줄임표 + 전문은 말풍선» 이었다. 그러면
   *    **무엇을 고쳐 다시 요청해야 하는지가 화면에서 사라져** 사유를 읽으려고 매번 「결과 확인」을
   *    눌러야 했다. 시안(SD-026)도 이 화면 계열에서 말줄임과 `title` 보완을 둘 다 거부한다
   *    (터치 환경에서 말풍선이 뜨지 않고, 게시본 정리기가 그 속성을 지운다).
   *    ⚠ 되살리지 말 것 — 행 카드는 줄이 늘어나도 손해가 아니라서 자를 이유가 없다.
   */
  it('★실패_사유는_자르지_않고_전문을_그_줄에서_보인다', () => {
    const reason = '증강 생성에 실패했습니다. 생성 조건을 바꾸어 다시 요청해 주세요.';
    mockList([row({ augSn: 6, resultReady: false, failRsnCn: reason })]);

    renderWithProviders(<PortalAugmentPage />);

    const fail = screen.getByTestId('portal-augment-fail-6');
    expect(fail).toHaveTextContent(reason);
    // 말줄임·말풍선 어느 쪽도 쓰지 않는다.
    expect(fail.className).not.toContain('truncate');
    expect(fail).not.toHaveAttribute('title');
    // 사유는 그 요청의 줄 안에 있다 — 별도 구역으로 빼지 않는다.
    expect(cell(6).getByTestId('portal-augment-fail-6')).toBe(fail);
  });

  it('실패가_아닌_행에는_사유_자리가_아예_없다', () => {
    mockList([
      row({ augSn: 7, resultReady: false, failRsnCn: null }),
      row({ augSn: 8, resultReady: true }),
    ]);

    renderWithProviders(<PortalAugmentPage />);

    expect(screen.queryByTestId('portal-augment-fail-7')).toBeNull();
    expect(screen.queryByTestId('portal-augment-fail-8')).toBeNull();
  });

  /**
   * ★ 조건은 **칩으로 흩는다** — 한 덩이 문자열로 이으면 폭이 모자랄 때 통째로 잘려 어느 값이
   *   사라졌는지조차 알 수 없다. 차례(아는 다섯 먼저)는 `generationCondition` 이 소유한다.
   */
  it('★생성_조건은_아는_다섯을_정해진_차례로_우리말_칩으로_보인다', () => {
    mockList([row({ augSn: 9, generationCondition: { severity: 'HIGH', time: 'NIGHT' } })]);

    renderWithProviders(<PortalAugmentPage />);

    const chips = cell(9).getAllByText(/^(시간대|계절|날씨|지형|심각도)$/);
    expect(chips.map((c) => c.textContent)).toEqual(['시간대', '심각도']);
    expect(cell(9).getByText('밤')).toBeInTheDocument();
    expect(cell(9).getByText('높음')).toBeInTheDocument();
  });
});

describe('증강 요청 현황 — 목록 행의 진입', () => {
  it('★목록_행에는_결과_확인만_둔다_후속_작업과_내려받기는_결과_확인_구역이_낸다', () => {
    mockList([row({ augSn: 10, resultReady: true, resultArrivedAt: '2026-09-02T10:00:00' })]);

    renderWithProviders(<PortalAugmentPage />);

    const actions = cell(10).getAllByRole('button');
    expect(actions).toHaveLength(1);
    expect(actions[0]).toHaveAccessibleName(/요청 결과 확인$/);
    expect(cell(10).queryByRole('link')).toBeNull();
  });

  it('★실패_행에도_결과_확인_말고는_아무_진입이_없다', () => {
    mockList([row({ augSn: 11, resultReady: false, failRsnCn: '실패했습니다.' })]);

    renderWithProviders(<PortalAugmentPage />);

    expect(cell(11).getAllByRole('button')).toHaveLength(1);
    expect(cell(11).queryByRole('link')).toBeNull();
    expect(cell(11).queryByText(/라벨링 이어서 하기/)).toBeNull();
    expect(cell(11).queryByText(/내려받기/)).toBeNull();
  });
});

describe('증강 요청 현황 — 재조회', () => {
  it('★실패한_요청은_재조회를_멈춘다', () => {
    expect(augmentPollIntervalFor([row({ resultReady: false, failRsnCn: '실패했습니다.' })])).toBe(
      false,
    );
  });

  it('도착한_요청도_재조회하지_않는다', () => {
    expect(augmentPollIntervalFor([row({ resultReady: true })])).toBe(false);
  });

  it('대기_중인_요청이_하나라도_있으면_재조회한다', () => {
    expect(
      augmentPollIntervalFor([
        row({ augSn: 1, resultReady: true }),
        row({ augSn: 2, resultReady: false, failRsnCn: '실패했습니다.' }),
        row({ augSn: 3, resultReady: false, failRsnCn: null }),
      ]),
    ).toBe(AUGMENT_POLL_MS);
  });

  it('요청이_하나도_없으면_재조회하지_않는다', () => {
    expect(augmentPollIntervalFor([])).toBe(false);
    expect(augmentPollIntervalFor(undefined)).toBe(false);
  });
});
