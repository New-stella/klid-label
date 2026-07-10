// R3 — 그라데이션·장식 제거(KRDS Don't: 그라데이션·brand 대면적 금지).
// PortalHome Hero 는 단색 KRDS 토큰 배경이어야 하며 bg-gradient-* 클래스가 없어야 한다.

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';

const useDatamartVideosMock = vi.fn();
vi.mock('@/features/portal/hooks/useDatamartVideos', () => ({
  useDatamartVideos: (params: { page?: number; size?: number }) => useDatamartVideosMock(params),
}));

import { PortalHomePage } from '../PortalHomePage';

describe('PortalHome_그라데이션_클래스_없음', () => {
  afterEach(() => {
    useDatamartVideosMock.mockReset();
    cleanup();
  });

  it('Hero_및_UI_크롬에_bg_gradient_클래스가_없다', () => {
    useDatamartVideosMock.mockReturnValue({
      data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 },
      isLoading: false,
      isError: false,
    });
    const { container } = renderWithProviders(<PortalHomePage />);

    // bg-gradient-* / from-* / to-* 그라데이션 유틸이 전혀 없어야 한다
    expect(container.querySelector('[class*="bg-gradient"]')).toBeNull();
    expect(container.innerHTML).not.toMatch(/bg-gradient-/);
  });
});
