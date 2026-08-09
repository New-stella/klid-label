import { describe, expect, it, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';

import { HealthStatusList } from '../HealthStatusList';

const useHealthMock = vi.fn();

vi.mock('@/features/health/hooks/useHealth', () => ({
  useHealth: () => useHealthMock(),
}));

describe('HealthStatusList 로딩 표시 (UI-089)', () => {
  beforeEach(() => {
    useHealthMock.mockReset();
  });

  // 사양은 로딩 **스켈레톤**인데 구 구현은 스피너였다. 5초 폴링이라 전환이 잦은 화면에서
  // 스피너는 목록 높이를 잃어 레이아웃이 흔들린다.
  it('로딩_중에는_스피너가_아니라_스켈레톤을_보여준다', () => {
    useHealthMock.mockReturnValue({ data: undefined, isLoading: true, error: null });
    const { container } = renderWithProviders(<HealthStatusList />);

    expect(screen.getByTestId('health-loading')).toBeInTheDocument();
    // Skeleton = animate-pulse 플레이스홀더
    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0);
    // Spinner(role=status)는 쓰지 않는다
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('조회_실패_시_에러_문구를_보여준다', () => {
    useHealthMock.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: new Error('boom'),
    });
    renderWithProviders(<HealthStatusList />);
    expect(screen.getByText('헬스 상태를 불러올 수 없습니다.')).toBeInTheDocument();
    expect(screen.queryByTestId('health-loading')).not.toBeInTheDocument();
  });

  it('정상_응답이면_컴포넌트별_상태를_아이콘과_텍스트로_함께_보여준다', () => {
    useHealthMock.mockReturnValue({
      isLoading: false,
      error: null,
      data: {
        status: 'UP',
        components: {
          db: { status: 'UP', details: { latencyMs: 3 } },
          aiServer: { status: 'DOWN' },
        },
      },
    });
    renderWithProviders(<HealthStatusList />);

    expect(screen.getByText('DB')).toBeInTheDocument();
    expect(screen.getByText('정상')).toBeInTheDocument();
    expect(screen.getByText('AI 서버')).toBeInTheDocument();
    expect(screen.getByText('연결 끊김')).toBeInTheDocument();
    expect(screen.queryByTestId('health-loading')).not.toBeInTheDocument();
  });
});
