// 회귀 가드 — 위험 작업 화면. [@design SCREEN-043] [@design UI-090]
//
// ★이 화면은 **완전한 자리표시**다 — 확인 절차를 거쳐도 서버를 부르지 않는다(API 호출 0건).
//   이 변경의 범위는 화면을 옮기는 것이고, 실제 실행 창구는 아직 없다. 여기에 임의로 요청을
//   붙이면 설계에 없는 파괴적 창구를 만들게 되므로 «호출이 0» 을 명시적으로 못 박는다.
//
// ★확인 절차는 화면 구성에서 뺄 수 없다 — 이 단계가 없으면 되돌릴 수 없는 작업이 한 번의
//   클릭으로 실행되는 사양이 된다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { useAdminSessionStore } from '@/features/adminSession/store';
import { apiClient } from '@/lib/api/client';
import { AdminMaintenancePage } from '@/pages/admin/AdminMaintenancePage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

describe('AdminMaintenancePage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: '1001', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
    });
    useAdminSessionStore.getState().open({
      token: 'dummy-window',
      expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useAdminSessionStore.getState().clear();
  });

  it('위험_작업_세_건이_전용_화면에_모여_있다', () => {
    renderWithProviders(<AdminMaintenancePage />, {
      initialEntries: ['/admin/maintenance'],
    });

    expect(screen.getByRole('button', { name: /시스템 초기화/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /배치 큐 초기화/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /캐시 삭제/ })).toBeInTheDocument();
  });

  it('★확인_절차를_거쳐도_서버를_부르지_않는다 — 자리표시다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AdminMaintenancePage />, {
      initialEntries: ['/admin/maintenance'],
    });

    const before = mock.history.post.length + mock.history.delete.length;

    await user.click(screen.getByRole('button', { name: /배치 큐 초기화/ }));
    // 누르는 것만으로 실행되지 않는다 — 확인 창을 먼저 거친다.
    await waitFor(() => {
      expect(screen.getAllByText(/되돌릴 수 없습니다/).length).toBeGreaterThanOrEqual(2);
    });
    await user.click(screen.getByRole('button', { name: '실행' }));

    expect(mock.history.post.length + mock.history.delete.length).toBe(before);
  });

  it('취소하면_아무_일도_일어나지_않는다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AdminMaintenancePage />, {
      initialEntries: ['/admin/maintenance'],
    });

    const before = mock.history.post.length + mock.history.delete.length;
    await user.click(screen.getByRole('button', { name: /캐시 삭제/ }));
    await user.click(await screen.findByRole('button', { name: '취소' }));

    expect(mock.history.post.length + mock.history.delete.length).toBe(before);
    expect(screen.queryByRole('button', { name: '실행' })).toBeNull();
  });

  it('남은_유효_시간을_표시하고_다시_확인할_통로를_준다', () => {
    renderWithProviders(<AdminMaintenancePage />, {
      initialEntries: ['/admin/maintenance'],
    });

    expect(screen.getByTestId('admin-session-remaining')).toHaveTextContent(
      /관리자 확인됨 — \d+:\d{2} 남음/,
    );
    expect(screen.getByRole('button', { name: '관리자 다시 확인' })).toBeInTheDocument();
  });

  it('유효창이_없으면_확인이_필요함을_알린다', () => {
    useAdminSessionStore.getState().clear();
    renderWithProviders(<AdminMaintenancePage />, {
      initialEntries: ['/admin/maintenance'],
    });

    expect(screen.getByTestId('admin-session-status')).toHaveTextContent('관리자 확인 필요');
    expect(screen.getByRole('button', { name: '관리자 확인' })).toBeInTheDocument();
  });
});
