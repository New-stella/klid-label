// 회귀 가드 — 포털 증강 화면(SCREEN-044). 요청 현황 · 결과 확인 · 후속 작업 진입 · 내려받기.
//
// 이 파일이 지키는 축 넷은 서로 다른 실패 모드다.
//  ① **결과가 도착한 요청에만** 후속 진입·내려받기가 뜬다 — 대기·실패에 뜨면 눌러 봐야 거절되는
//     자리가 되어 회복 경로를 잘못 안내한다.
//  ② **요청을 거는 자리를 이 화면에 두지 않는다** — 대상 영상을 고르러 가는 링크만 둔다.
//     같은 행위의 진입이 둘이 되면 어느 쪽이 정본인지 알 수 없다.
//  ③ **채택·반려가 없다** — 이 경로에는 검수가 없어 그 결정 단계 자체가 없다.
//  ④ 대기·실패를 빈 화면으로 얼버무리지 않는다 — 대기는 대기라고, 실패는 사유와 함께 알린다.
//
// ★ ①·③ 은 「없다」만 단언하면 화면이 통째로 아무것도 그리지 않아도 통과한다. 그래서 **존치
//   축을 짝으로** 둔다(결과가 도착한 요청에서는 그 둘이 실제로 뜬다).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import { formatDateTime } from '@/features/review/formatDateTime';
import type {
  PortalAugmentDetail,
  PortalAugmentSummary,
} from '@/features/portal/augments/types';

import { PortalAugmentPage } from '../PortalAugmentPage';

const usePortalAugmentsMock = vi.fn();
const usePortalAugmentMock = vi.fn();

vi.mock('@/features/portal/augments/hooks/usePortalAugments', () => ({
  usePortalAugments: (params: unknown) => usePortalAugmentsMock(params),
}));
vi.mock('@/features/portal/augments/hooks/usePortalAugment', () => ({
  usePortalAugment: (augSn: unknown) => usePortalAugmentMock(augSn),
}));
// 미리보기는 계약에 전용 창구가 없어 포털 업로드 자산 경로를 빌려 쓴다 — 여기서는 통신을 막고
// 「미리보기가 없어도 나머지가 멀쩡하다」(조용히 비운다)를 함께 확인한다.
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

function detail(over: Partial<PortalAugmentDetail> = {}): PortalAugmentDetail {
  return {
    augSn: 9001,
    uldSn: 501,
    augSttsCd: 'ACCEPTED',
    failRsnCn: null,
    orgnlFileNm: 'street.mp4',
    requestedAt: '2026-09-01T10:00:00',
    resultReady: false,
    resultUldSn: null,
    resultArrivedAt: null,
    generationCondition: { weather: 'RAIN' },
    ...over,
  };
}

function mockList(rows: PortalAugmentSummary[], totalPages = 1) {
  usePortalAugmentsMock.mockReturnValue({
    data: { content: rows, totalElements: rows.length, totalPages, number: 0, size: 20 },
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  });
}

function mockDetail(d: PortalAugmentDetail | undefined, over: Record<string, unknown> = {}) {
  usePortalAugmentMock.mockReturnValue({
    data: d,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
    ...over,
  });
}

/** 목록의 「결과 확인」을 눌러 상세 구역을 연다. */
async function openResult(target: PortalAugmentSummary) {
  const user = userEvent.setup();
  await user.click(
    screen.getByRole('button', {
      name: `${target.orgnlFileNm} ${formatDateTime(target.requestedAt)} 요청 결과 확인`,
    }),
  );
  return within(screen.getByTestId('portal-augment-result'));
}

beforeEach(() => {
  usePortalAugmentsMock.mockReset();
  usePortalAugmentMock.mockReset();
  mockDetail(undefined);
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('포털 증강 화면 — 요청 현황', () => {
  it('본인이_낸_요청이_요청_일시_대상영상_생성조건_상태와_함께_목록에_뜬다', () => {
    mockList([row()]);

    renderWithProviders(<PortalAugmentPage />);

    const tr = screen.getByTestId('portal-augment-row-9001');
    expect(within(tr).getByText('street.mp4')).toBeInTheDocument();
    // 생성 조건은 아는 항목이면 우리말 이름·우리말 값으로 보인다(표기 규칙은
    // `generationCondition` 이 소유하고 `PortalAugmentListOutcome` 가 그 차례를 지킨다).
    expect(within(tr).getByText('날씨: 비')).toBeInTheDocument();
    expect(within(tr).getByText(formatDateTime('2026-09-01T10:00:00'))).toBeInTheDocument();
  });

  it('★대기_도착_실패가_상태로_구분된다', () => {
    mockList([
      row({ augSn: 1, resultReady: false }),
      row({ augSn: 2, resultReady: true, resultArrivedAt: '2026-09-01T10:42:00' }),
      row({ augSn: 3, resultReady: false, failRsnCn: '외부 위탁 거절' }),
    ]);

    renderWithProviders(<PortalAugmentPage />);

    expect(within(screen.getByTestId('portal-augment-row-1')).getByText('결과 대기 중')).toBeInTheDocument();
    expect(within(screen.getByTestId('portal-augment-row-2')).getByText('결과 도착')).toBeInTheDocument();
    expect(within(screen.getByTestId('portal-augment-row-3')).getByText('실패')).toBeInTheDocument();
  });

  it('대기_구간을_숨기지_않는다_즉시_결과가_나오지_않는다는_안내가_있다', () => {
    mockList([row()]);

    renderWithProviders(<PortalAugmentPage />);

    expect(
      screen.getByText('요청한 즉시 결과가 나오지 않습니다 — 도착하면 목록의 상태가 바뀝니다.'),
    ).toBeInTheDocument();
  });

  it('요청이_하나도_없으면_안내와_영상_고르러_가는_길을_함께_보인다', () => {
    mockList([]);

    renderWithProviders(<PortalAugmentPage />);

    expect(screen.getByText('아직 요청한 증강이 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '증강할 영상 고르러 가기' })).toHaveAttribute(
      'href',
      '/portal/uploads',
    );
  });

  it('조회_실패는_0건과_구분한다_낸_요청이_사라진_것이_아니다', () => {
    usePortalAugmentsMock.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('boom'),
      refetch: vi.fn(),
    });

    renderWithProviders(<PortalAugmentPage />);

    expect(screen.getByText('요청 현황을 불러올 수 없습니다')).toBeInTheDocument();
    expect(screen.queryByText('아직 요청한 증강이 없습니다.')).toBeNull();
  });
});

describe('포털 증강 화면 — 요청을 거는 자리를 두지 않는다', () => {
  it('★대상_영상을_고르러_가는_링크만_있고_요청을_접수하는_조작이_없다', () => {
    mockList([row()]);

    renderWithProviders(<PortalAugmentPage />);

    // 존치 축 — 링크는 있어야 한다(없으면 다음에 무엇을 할지 찾지 못한다).
    expect(screen.getByRole('link', { name: '증강할 영상 고르러 가기' })).toHaveAttribute(
      'href',
      '/portal/uploads',
    );
    // 제거 축 — 요청을 거는 버튼은 없다. 그 자리는 포털 업로드 화면의 자산별 액션이다.
    expect(screen.queryByRole('button', { name: /증강.*요청/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /요청하기/ })).toBeNull();
  });
});

describe('포털 증강 화면 — 결과 확인', () => {
  it('★기다리는_중인_요청은_대기임을_알리고_결과_영역을_비운다', async () => {
    mockList([row()]);
    mockDetail(detail({ resultReady: false }));

    renderWithProviders(<PortalAugmentPage />);
    const box = await openResult(row());

    expect(box.getByTestId('portal-augment-waiting')).toHaveTextContent(
      '결과가 아직 도착하지 않았습니다.',
    );
    // 결과가 없으므로 후속 작업도 내려받기도 없다.
    expect(box.queryByRole('link', { name: '라벨링 이어서 하기' })).toBeNull();
    expect(box.queryByRole('button', { name: /내려받기|내보내기/ })).toBeNull();
  });

  it('★실패한_요청은_실패_사실과_사유를_함께_보이고_후속_진입을_열지_않는다', async () => {
    mockList([row()]);
    mockDetail(detail({ resultReady: false, failRsnCn: '외부 위탁 거절' }));

    renderWithProviders(<PortalAugmentPage />);
    const box = await openResult(row());

    const alert = box.getByTestId('portal-augment-failed');
    expect(alert).toHaveTextContent('증강이 실패했습니다');
    expect(alert).toHaveTextContent('외부 위탁 거절');
    expect(box.queryByRole('link', { name: '라벨링 이어서 하기' })).toBeNull();
    expect(box.queryByRole('button', { name: /내려받기|내보내기/ })).toBeNull();
  });

  it('★결과가_도착한_요청에만_후속_작업_진입과_내려받기가_뜬다', async () => {
    mockList([row({ resultReady: true, resultArrivedAt: '2026-09-01T10:42:00' })]);
    mockDetail(
      detail({ resultReady: true, resultUldSn: 802, resultArrivedAt: '2026-09-01T10:42:00' }),
    );

    renderWithProviders(<PortalAugmentPage />);
    const box = await openResult(row({ resultReady: true }));

    // 후속 작업은 포털 라벨링 화면이 수행하고 이 화면은 그리로 가는 진입만 갖는다.
    // 주소는 결과물 자산 식별자(802)로 조립된다 — 요청 식별자나 대상 영상이 아니다.
    expect(box.getByRole('link', { name: '라벨링 이어서 하기' })).toHaveAttribute(
      'href',
      '/portal/label/802?source=upload',
    );
    expect(box.getByRole('button', { name: '증강 결과물 라벨 내보내기' })).toBeInTheDocument();
    expect(box.getByRole('button', { name: '증강 결과물 원본 파일 내려받기' })).toBeInTheDocument();
    expect(box.getByText('본인이 낸 요청의 결과물만 내려받을 수 있습니다.')).toBeInTheDocument();
    // 후속 작업 범위를 미리 알린다 — 이 경로에 없는 것을 기대하지 않도록.
    expect(
      box.getByText('후속 작업에서는 바운딩 박스·폴리곤 수동 라벨링만 제공합니다.'),
    ).toBeInTheDocument();
  });

  it('★채택_반려로_가르는_결정_단계가_없다_이_경로엔_검수가_없다', async () => {
    mockList([row({ resultReady: true })]);
    mockDetail(detail({ resultReady: true, resultUldSn: 802 }));

    renderWithProviders(<PortalAugmentPage />);
    const box = await openResult(row({ resultReady: true }));

    // 제거 축 — 검수자 결정 축의 조작이 하나도 없다.
    expect(box.queryByRole('button', { name: /채택|반려|승인/ })).toBeNull();
    // 존치 축 — 그렇다고 결과 구역이 비어 있는 것은 아니다(확인한 결과물은 그대로 이어진다).
    expect(box.getByRole('link', { name: '라벨링 이어서 하기' })).toBeInTheDocument();
  });

  it('결과가_도착하지_않은_요청은_도착_일시_자리를_비운다', async () => {
    mockList([row()]);
    mockDetail(detail({ resultReady: false }));

    renderWithProviders(<PortalAugmentPage />);
    const box = await openResult(row());

    expect(box.getByText('결과 도착 일시')).toBeInTheDocument();
    expect(box.getByText('-')).toBeInTheDocument();
  });

  it('상세_조회가_실패하면_대기로_읽지_않고_실패를_알린다', async () => {
    mockList([row()]);
    mockDetail(undefined, { isError: true });

    renderWithProviders(<PortalAugmentPage />);
    const box = await openResult(row());

    expect(box.getByText('요청 정보를 불러올 수 없습니다')).toBeInTheDocument();
    expect(box.queryByTestId('portal-augment-waiting')).toBeNull();
  });
});
