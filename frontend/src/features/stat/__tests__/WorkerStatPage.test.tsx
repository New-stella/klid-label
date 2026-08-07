import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { WorkerStatPage } from '@/pages/WorkerStatPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

function setRole(role: 'WORKER' | 'REVIEWER') {
  useAuthStore.setState({
    token: 'fake',
    claims: {
      sub: '11',
      role,
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

const sample = {
  workerId: '11',
  workerName: '홍길동',
  completed: 1234,
  inProgress: 5,
  rejected: 8,
  labelCount: 9876,
  autoLabelRate: 0.42,
  rejectRate: 0.05,
  dailyCompletion: [
    { date: '2026-05-01', count: 10 },
    { date: '2026-05-02', count: 20 },
  ],
  monthly: [
    { month: '2026-04', completed: 500, rejected: 3, labelCount: 1000 },
  ],
};

describe('WorkerStatPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/stats/worker').reply(200, {
      success: true,
      data: sample,
      message: null,
      errorCode: null,
    });
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 0 },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('WorkerStatPage_KPI_4개_노출', async () => {
    setRole('WORKER');
    renderWithProviders(<WorkerStatPage />);

    await waitFor(() => {
      expect(screen.getByText('1,234')).toBeInTheDocument();
    });

    const grid = screen.getByTestId('worker-kpi-grid');
    // KpiCard 는 label 을 <p class="truncate text-sm font-medium text-gray-500"> 로 렌더한다.
    const labels = Array.from(grid.querySelectorAll('p')).map((el) => el.textContent);
    expect(labels).toContain('완료 작업');
    expect(labels).toContain('작업중');
    expect(labels).toContain('반려');
    expect(labels).toContain('총 라벨 수');
    // KpiCard 가 4개 이상 렌더되었는지 확인 (라벨 <p> + 값 <p> 페어)
    expect(grid.querySelectorAll('p').length).toBeGreaterThanOrEqual(8);
  });

  it('월별_표_노출', async () => {
    setRole('WORKER');
    renderWithProviders(<WorkerStatPage />);

    const tbl = await screen.findByTestId('worker-monthly-table');
    await waitFor(() => {
      expect(tbl.textContent).toContain('2026-04');
    });
  });

  // ── 사양 SCREEN-020 정합 회귀 가드 ─────────────────────────────────

  it('작업자를_고르기_전에는_첫_작업자로_자동폴백하지_않고_안내가_뜬다', async () => {
    // given: REVIEWER 이고 작업자 목록이 있다.
    // 구 동작은 `workers[0]` 로 자동 폴백해, 아무도 고르지 않았는데 특정 작업자의 통계가
    // 사실처럼 표시됐다(화면의 숫자가 누구 것인지 사용자가 선택한 적 없음) — 그 폴백은 폐기됐다.
    setRole('REVIEWER');
    mock.onGet('/users').reply(200, {
      success: true,
      data: {
        content: [
          { id: 11, name: '홍길동', role: 'WORKER', active: true, loginId: 'w1' },
          { id: 12, name: '김철수', role: 'WORKER', active: true, loginId: 'w2' },
        ],
        totalElements: 2,
        totalPages: 1,
        number: 0,
        size: 100,
      },
      message: null,
      errorCode: null,
    });

    // when
    renderWithProviders(<WorkerStatPage />);

    // then: KPI·차트·표 대신 미선택 안내가 그 자리를 대신한다
    expect(await screen.findByTestId('worker-stat-empty')).toBeInTheDocument();
    expect(
      screen.getByText('상단에서 작업자를 선택하면 해당 작업자의 통계가 표시됩니다.'),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('worker-kpi-grid')).toBeNull();
    expect(screen.queryByTestId('worker-monthly-table')).toBeNull();
    // 폴백이 살아 있었다면 첫 작업자 통계(1,234)가 그려졌을 것이다.
    expect(screen.queryByText('1,234')).toBeNull();
  });

  it('WORKER는_본인_고정이라_미선택_안내에_도달하지_않는다', async () => {
    // given / when: WORKER 는 claims.sub 로 대상이 고정된다
    setRole('WORKER');
    renderWithProviders(<WorkerStatPage />);

    // then
    await waitFor(() => {
      expect(screen.getByTestId('worker-kpi-grid')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('worker-stat-empty')).toBeNull();
  });

  it('제목과_부제가_역할별로_분기된다', async () => {
    // given / when: WORKER 시각
    setRole('WORKER');
    const { unmount } = renderWithProviders(<WorkerStatPage />);

    // then
    expect(await screen.findByText('나의 통계')).toBeInTheDocument();
    expect(screen.getByText('나의 작업 통계를 확인합니다.')).toBeInTheDocument();
    unmount();

    // given / when: REVIEWER 시각
    setRole('REVIEWER');
    renderWithProviders(<WorkerStatPage />);

    // then
    expect(await screen.findByText('작업자 통계')).toBeInTheDocument();
    expect(screen.getByText('작업자별 통계를 확인합니다.')).toBeInTheDocument();
  });
});
