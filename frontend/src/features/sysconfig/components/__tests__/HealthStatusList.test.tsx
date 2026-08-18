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

  // 픽스처의 components 키는 BE ManageHealthController 가 실제로 넣는 3종이다.
  // (구 픽스처는 `db` 를 썼는데 서버는 `database` 를 보내며, 조회표에 그 키가 없어
  //  화면에 원문 키 `database` 가 그대로 노출되던 결함이 이 픽스처 때문에 가려져 있었다.)
  it('정상_응답이면_컴포넌트별_상태를_아이콘과_텍스트로_함께_보여준다', () => {
    useHealthMock.mockReturnValue({
      isLoading: false,
      error: null,
      data: {
        status: 'UP',
        components: {
          deidentify: { status: 'UP' },
          aiServer: { status: 'DOWN' },
          database: { status: 'UP', details: { service: 'control-db' } },
        },
      },
    });
    renderWithProviders(<HealthStatusList />);

    expect(screen.getByText('비식별 서버')).toBeInTheDocument();
    expect(screen.getByText('데이터베이스')).toBeInTheDocument();
    // 원문 키가 표시명 대신 새어 나오지 않는다(componentLabels[key] ?? key 폴백)
    expect(screen.queryByText('database')).not.toBeInTheDocument();
    expect(screen.getAllByText('정상').length).toBe(2);
    expect(screen.getByText('AI 서버')).toBeInTheDocument();
    expect(screen.getByText('연결 끊김')).toBeInTheDocument();
    expect(screen.queryByTestId('health-loading')).not.toBeInTheDocument();
  });

  // latencyMs 조건부 표기는 **현재 어떤 인디케이터도 그 값을 싣지 않아 실응답으로는 도달하지
  // 않는다**(BE 는 details 에 service/error 만 넣는다). 계약이 넓어지면 살아나는 경로라
  // 그대로 두고, 회귀로 죽지 않게 여기서만 명시적으로 덮는다.
  it('details에_latencyMs가_있으면_ms로_함께_표기한다_현재_BE는_미전송', () => {
    useHealthMock.mockReturnValue({
      isLoading: false,
      error: null,
      data: {
        status: 'UP',
        components: { aiServer: { status: 'UP', details: { latencyMs: 3 } } },
      },
    });
    renderWithProviders(<HealthStatusList />);

    expect(screen.getByText('3ms')).toBeInTheDocument();
  });
});
