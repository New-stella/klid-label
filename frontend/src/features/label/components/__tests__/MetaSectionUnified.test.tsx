// R3 GUI 통일 — 프레임 설명 ↔ 시계열 메타 두 섹션이 동일한 공통 섹션 래퍼(MetaSection)를
// 사용해 헤더·여백·토글 스타일이 통일되는지 검증.
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

const { mockUseFrameDescription, mockUseUpdateFrameDescription } = vi.hoisted(() => ({
  mockUseFrameDescription: vi.fn(),
  mockUseUpdateFrameDescription: vi.fn(),
}));
const { mockUseMeta, mockUseUpdateMeta } = vi.hoisted(() => ({
  mockUseMeta: vi.fn(),
  mockUseUpdateMeta: vi.fn(),
}));

vi.mock('../../hooks/useFrameDescription', () => ({
  useFrameDescription: mockUseFrameDescription,
  useUpdateFrameDescription: mockUseUpdateFrameDescription,
}));
vi.mock('@/features/auto/hooks/useMeta', () => ({ useMeta: mockUseMeta }));
vi.mock('@/features/auto/hooks/useUpdateMeta', () => ({ useUpdateMeta: mockUseUpdateMeta }));

import { FrameDescriptionPanel } from '../FrameDescriptionPanel';
import { TimeseriesSidePanel } from '../TimeseriesSidePanel';
import { MetaSection } from '../MetaSection';

describe('MetaSection 공통 래퍼', () => {
  it('title과_children을_접이식_섹션으로_렌더', () => {
    render(
      <MetaSection title="공통 섹션">
        <p>내용</p>
      </MetaSection>,
    );
    expect(screen.getByRole('button', { name: /공통 섹션/ })).toBeInTheDocument();
    expect(screen.getByTestId('meta-section')).toBeInTheDocument();
    expect(screen.getByText('내용')).toBeInTheDocument();
  });
});

describe('프레임설명·시계열메타 GUI 통일', () => {
  beforeEach(() => {
    mockUseFrameDescription.mockReturnValue({
      data: { srcSn: 1, description: '설명' },
      isLoading: false,
      isError: false,
    });
    mockUseUpdateFrameDescription.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
      isError: false,
    });
    mockUseMeta.mockReturnValue({
      data: {
        items: [{ metaSn: 1, metaKey: '0001', metaVal: '메타' }],
        vlmText: '메타',
        stateChanges: [],
      },
      isLoading: false,
      error: null,
    });
    mockUseUpdateMeta.mockReturnValue({ mutate: vi.fn(), isPending: false });
  });

  it('프레임설명_시계열메타_동일_섹션_스타일_통일', () => {
    renderWithProviders(
      <>
        <FrameDescriptionPanel srcSn={1} />
        <TimeseriesSidePanel srcSn={1} />
      </>,
    );

    // then — 두 패널 모두 공통 MetaSection 래퍼(meta-section)를 사용
    const sections = screen.getAllByTestId('meta-section');
    expect(sections).toHaveLength(2);

    // 두 섹션 헤더 버튼 클래스가 동일 (통일된 스타일)
    const headers = sections.map(
      (s) => s.querySelector('button')?.getAttribute('class') ?? '',
    );
    expect(headers[0]).not.toBe('');
    expect(headers[0]).toBe(headers[1]);
  });
});
