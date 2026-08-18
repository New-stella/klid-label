// 회귀 가드 — 포털 홈 데이터마트 영상 목록의 페이지 이동 수단.
//
// 구 동작: `useDatamartVideos({ page: 0, size: 20 })` 로 page 가 0 에 고정돼 있고 페이저가 없어
// **첫 20건 밖의 영상에 도달할 수단이 아예 없었다**. 서버는 페이징으로 내려주는데 화면에
// 페이지를 옮길 컨트롤이 없으면 나머지 영상은 존재해도 고를 수 없다(SCREEN-028 사양).
import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { DatamartVideo } from '@/features/portal/api';

import { PortalHomePage } from '../PortalHomePage';

const useDatamartVideosMock = vi.fn();
vi.mock('@/features/portal/hooks/useDatamartVideos', () => ({
  useDatamartVideos: (params: { page?: number; size?: number }) => useDatamartVideosMock(params),
}));

function video(over: Partial<DatamartVideo> = {}): DatamartVideo {
  return {
    rawSn: 10,
    title: 'CLIP-10',
    eventName: 'FALL',
    frameCount: 5,
    firstSrcSn: 100,
    lastUpdatedAt: '2026-06-01T10:00:00',
    myLabelExpiresAt: null,
    ...over,
  };
}

/** 서버가 여러 페이지를 내려주는 상황(전체 건수 > 한 페이지). */
function mockPaged(totalPages: number, totalElements: number) {
  useDatamartVideosMock.mockReturnValue({
    data: {
      content: [video()],
      totalElements,
      totalPages,
      number: 0,
      size: 20,
    },
    isLoading: false,
    isError: false,
  });
}

afterEach(() => {
  useDatamartVideosMock.mockReset();
});

describe('PortalHomePage 페이지 이동', () => {
  it('여러_페이지면_공용_페이저가_렌더된다', () => {
    mockPaged(3, 45);
    renderWithProviders(<PortalHomePage />);

    // 공용 Pagination(UI-008) — 양끝 + 현재 앞뒤 1칸 규칙을 가진 그 컨트롤이어야 한다.
    expect(screen.getByRole('navigation', { name: '페이지네이션' })).toBeInTheDocument();
    // 번호 없는 약식 이전/다음이 아니라 페이지 번호로 직접 갈 수 있어야 한다.
    expect(screen.getByRole('button', { name: '2페이지' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '3페이지' })).toBeInTheDocument();
  });

  it('다음_페이지로_이동하면_조회_파라미터의_page_가_증가한다', async () => {
    const user = userEvent.setup();
    mockPaged(3, 45);
    renderWithProviders(<PortalHomePage />);

    // 최초 조회는 첫 페이지
    expect(useDatamartVideosMock).toHaveBeenCalledWith(expect.objectContaining({ page: 0 }));

    await user.click(screen.getByRole('button', { name: '다음 페이지' }));

    // 두 번째 페이지를 실제로 서버에 요청해야 한다 — 컨트롤만 그리고 파라미터가 그대로면
    // 도달 불가는 그대로다.
    await waitFor(() =>
      expect(useDatamartVideosMock).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })),
    );
  });

  it('페이지_번호를_직접_눌러도_그_페이지를_조회한다', async () => {
    const user = userEvent.setup();
    mockPaged(3, 45);
    renderWithProviders(<PortalHomePage />);

    await user.click(screen.getByRole('button', { name: '3페이지' }));

    await waitFor(() =>
      expect(useDatamartVideosMock).toHaveBeenCalledWith(expect.objectContaining({ page: 2 })),
    );
  });

  it('전체가_한_페이지면_페이저를_그리지_않는다', () => {
    // 사양(SCREEN-028): 전체가 한 페이지에 들어오면 페이저를 그리지 않는다.
    mockPaged(1, 3);
    renderWithProviders(<PortalHomePage />);

    expect(screen.queryByRole('navigation', { name: '페이지네이션' })).toBeNull();
  });

  it('주소의_page_값을_읽어_그_페이지로_진입한다', () => {
    // 뒤로가기·북마크가 동작하려면 페이지가 주소에 남아야 한다(SCREEN-028).
    mockPaged(3, 45);
    renderWithProviders(<PortalHomePage />, { initialEntries: ['/portal?page=2'] });

    expect(useDatamartVideosMock).toHaveBeenCalledWith(expect.objectContaining({ page: 2 }));
  });
});
