// 회귀 가드 — 포털 업로드 자산 목록의 ① 페이지 이동 수단 ② 조회 실패 표시.
//
// 구 동작 두 가지가 모두 결함이었다.
//  ① `usePortalUploads({ page: 0, size: 20 })` 로 page 가 0 고정 + 페이저 없음 →
//     첫 20건 밖의 자산에 도달할 수단이 없었다.
//  ② `isError` 분기가 없어 조회가 실패하면 data 가 undefined → uploads 가 [] 로 떨어져
//     "아직 올린 자산이 없습니다" 가 떴다. **사용자는 서버 오류를 자기 자산이 사라진 것으로
//     오해한다.** "조회 실패" 와 "실제로 0건" 은 반드시 구분돼야 한다(SD-033 ③ 목록 조회 실패).
import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
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
    // 신규 접수는 영상뿐이라 기본 픽스처도 영상이다.
    uldTypeCd: 'VIDEO',
    orgnlFileNm: 'a.mp4',
    fileSz: 1024,
    mimeTypeNm: 'video/mp4',
    uldSttsCd: 'READY',
    frmeCnt: 1,
    frmeSn: 10,
    regDt: '2026-07-17T00:00:00',
    expiresAt: null,
    ...over,
  };
}

function mockPaged(totalPages: number, totalElements: number) {
  usePortalUploadsMock.mockReturnValue({
    data: { content: [up()], totalElements, totalPages, number: 0, size: 20 },
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  });
}

/** 조회 실패 — React Query 는 data 를 undefined 로 둔다(그래서 구 동작이 "0건" 으로 보였다). */
function mockError(refetch = vi.fn()) {
  usePortalUploadsMock.mockReturnValue({
    data: undefined,
    isLoading: false,
    isError: true,
    error: new Error('boom'),
    refetch,
  });
  return refetch;
}

function mockSideEffects() {
  useDeleteUploadMock.mockReturnValue({
    deleteAsync: vi.fn().mockResolvedValue(undefined),
    isPending: false,
    error: null,
  });
}

afterEach(() => {
  usePortalUploadsMock.mockReset();
  useDeleteUploadMock.mockReset();
  vi.restoreAllMocks();
});

describe('PortalUploadPage 목록 페이지 이동', () => {
  it('여러_페이지면_공용_페이저가_렌더된다', () => {
    mockSideEffects();
    mockPaged(3, 45);
    renderWithProviders(<PortalUploadPage />);

    // ⚠ 2026-09-16 — 킷 페이저는 싸개에 `nav` 역할을 두지 않고 쪽 번호를 **버튼이 아니라
    //   링크**(`a.page-link`)로 그린다. 구 기대값(`navigation · 페이지네이션` · `button · 2페이지`)
    //   은 폐기. 지키는 축은 그대로다 — 여러 쪽이면 옮길 수단이 화면에 선다.
    expect(screen.getByRole('link', { name: '2' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다음' })).toBeInTheDocument();
  });

  it('다음_페이지로_이동하면_조회_파라미터의_page_가_증가한다', async () => {
    const user = userEvent.setup();
    mockSideEffects();
    mockPaged(3, 45);
    renderWithProviders(<PortalUploadPage />);

    expect(usePortalUploadsMock).toHaveBeenCalledWith(expect.objectContaining({ page: 0 }));

    // ⚠ 2026-09-16 — 킷 페이저의 다음 걸음은 글이 「다음」이다(구 기대값 「다음 페이지」 폐기).
    await user.click(screen.getByRole('button', { name: '다음' }));

    await waitFor(() =>
      expect(usePortalUploadsMock).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })),
    );
  });

  it('전체가_한_페이지면_페이저를_그리지_않는다', () => {
    mockSideEffects();
    mockPaged(1, 3);
    renderWithProviders(<PortalUploadPage />);

    // ⚠ 2026-09-16 — 위와 같은 사유로 쪽 번호 링크가 없다는 것으로 부재를 본다.
    expect(screen.queryByRole('link', { name: '2' })).toBeNull();
    expect(screen.queryByRole('button', { name: '다음' })).toBeNull();
  });
});

describe('PortalUploadPage 목록 조회 실패', () => {
  it('조회_실패를_빈_상태로_오표시하지_않는다', () => {
    mockSideEffects();
    mockError();
    renderWithProviders(<PortalUploadPage />);

    // 핵심 — "없습니다" 가 뜨면 사용자는 자기 자산이 사라진 것으로 오해한다.
    expect(screen.queryByText(/아직 올린 자산이 없습니다/)).toBeNull();
  });

  it('조회_실패시_오류_안내와_다시_시도가_노출된다', () => {
    mockSideEffects();
    mockError();
    renderWithProviders(<PortalUploadPage />);

    expect(screen.getByText(/목록을 불러올 수 없습니다/)).toBeInTheDocument();
    // 자산이 사라진 것이 아님을 명시한다(SD-033 ③ 문구).
    expect(screen.getByText(/사라진 것은 아닙니다/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });

  it('다시_시도를_누르면_재조회한다', async () => {
    const user = userEvent.setup();
    mockSideEffects();
    const refetch = mockError();
    renderWithProviders(<PortalUploadPage />);

    await user.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('실제로_0건이면_빈_상태를_그대로_보여준다', () => {
    // 회귀 방지의 반대 축 — 오류 처리를 넣느라 "정말 0건" 이 안내를 잃으면 안 된다.
    mockSideEffects();
    usePortalUploadsMock.mockReturnValue({
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      isLoading: false,
      isError: false,
      error: null,
      refetch: vi.fn(),
    });
    renderWithProviders(<PortalUploadPage />);

    expect(screen.getByText(/아직 올린 자산이 없습니다/)).toBeInTheDocument();
    expect(screen.queryByText(/목록을 불러올 수 없습니다/)).toBeNull();
  });
});
