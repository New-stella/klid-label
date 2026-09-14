// R3 GUI 통일 — 메타 탭의 접이식 섹션들이 동일한 공통 섹션 래퍼(MetaSection)를 사용해
// 헤더·여백·토글 스타일이 통일되는지 검증.
//
// ⚠ 2026-09-14 — 짝을 프레임 설명 ↔ <b>촬영환경</b>으로 바꿨다. 영상 분석 설명(구 시계열 메타)이
//   접이식 섹션에서 <b>창의 칸</b>으로 옮겨가 이 래퍼를 더 이상 쓰지 않기 때문이다(회귀가 아니라
//   전제 변경). 이 시험이 지키는 것은 「메타 탭의 섹션들이 같은 래퍼를 쓴다」이지 특정 두 패널의
//   조합이 아니다.
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

const { mockUseFrameDescription, mockUseUpdateFrameDescription } = vi.hoisted(() => ({
  mockUseFrameDescription: vi.fn(),
  mockUseUpdateFrameDescription: vi.fn(),
}));
const { mockUseEnvironmentMeta, mockUseUpdateEnvironmentMeta } = vi.hoisted(() => ({
  mockUseEnvironmentMeta: vi.fn(),
  mockUseUpdateEnvironmentMeta: vi.fn(),
}));

vi.mock('../../hooks/useFrameDescription', () => ({
  useFrameDescription: mockUseFrameDescription,
  useUpdateFrameDescription: mockUseUpdateFrameDescription,
}));
vi.mock('../../hooks/useEnvironmentMeta', () => ({
  useEnvironmentMeta: mockUseEnvironmentMeta,
  useUpdateEnvironmentMeta: mockUseUpdateEnvironmentMeta,
}));

import { EnvironmentMetaPanel } from '../EnvironmentMetaPanel';
import { FrameDescriptionPanel } from '../FrameDescriptionPanel';
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

describe('메타 탭 섹션 GUI 통일', () => {
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
    mockUseEnvironmentMeta.mockReturnValue({
      data: { rawSn: 1, weather: null, timeOfDay: null, season: null },
      isLoading: false,
      isError: false,
    });
    mockUseUpdateEnvironmentMeta.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
      isError: false,
    });
  });

  it('프레임설명_촬영환경_동일_섹션_스타일_통일', () => {
    renderWithProviders(
      <>
        <FrameDescriptionPanel srcSn={1} />
        <EnvironmentMetaPanel rawSn={1} />
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
