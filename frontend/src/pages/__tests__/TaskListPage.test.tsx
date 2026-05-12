import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { TaskListPage } from '@/pages/TaskListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function setRole(role: 'REVIEWER' | 'WORKER', sub = 'u-7') {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub, role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

describe('TaskListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('priority_deadline_컬럼_미사용_(UI_UX_4_5_정합)', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 100,
            videoId: 1,
            cctvName: 'CCTV-1',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-1')).toBeInTheDocument();
    });

    // priority/deadline 컬럼 헤더는 절대 노출되지 않음
    expect(screen.queryByText('우선순위')).not.toBeInTheDocument();
    expect(screen.queryByText('기한')).not.toBeInTheDocument();
    expect(screen.queryByText('마감일')).not.toBeInTheDocument();
  });

  it('작업_없을_때_EmptyState_노출', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('배정된 작업이 없습니다')).toBeInTheDocument();
    });
  });

  it('WORKER는_본인_배정만_보이고_미배정_완료영상은_노출되지_않음', async () => {
    setRole('WORKER');
    // BE: /assignments 는 WORKER 본인 배정만 반환 (videoId=1)
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 100,
            videoId: 1,
            cctvName: 'CCTV-MY-1',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
    // /videos 는 완료 영상 2건 (videoId=1, 2). videoId=2 는 다른 사람에게 배정되었거나 미배정.
    mock.onGet('/videos').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 1,
            cctvName: 'CCTV-MY-1',
            vmsClipId: '',
            eventName: '',
            eventTypeCd: '',
            localGov: '',
            frameCount: 0,
            status: 'COMPLETED',
            capturedAt: '2026-05-07T10:00:00Z',
          },
          {
            id: 2,
            cctvName: 'CCTV-OTHER-2',
            vmsClipId: '',
            eventName: '',
            eventTypeCd: '',
            localGov: '',
            frameCount: 0,
            status: 'COMPLETED',
            capturedAt: '2026-05-07T10:00:00Z',
          },
        ],
        totalElements: 2,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 100 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-MY-1')).toBeInTheDocument();
    });
    // 다른 영상(미배정 노이즈)은 WORKER 화면에 노출되지 않아야 한다.
    expect(screen.queryByText('CCTV-OTHER-2')).not.toBeInTheDocument();
    expect(screen.queryByText('미배정')).not.toBeInTheDocument();
  });

  it('WORKER로_TaskListPage_진입_시_users_API_호출되지_않음', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 100,
            videoId: 1,
            cctvName: 'CCTV-1',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 999 },
      message: null,
      errorCode: null,
    });
    // /users 와 /users/workers 는 호출되면 403 — 호출 자체가 일어나면 안 됨.
    mock.onGet('/users').reply(403);
    mock.onGet('/users/workers').reply(403);

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-1')).toBeInTheDocument();
    });

    // WORKER 진입 시 /users 와 /users/workers 호출이 0회여야 한다.
    const callsToUsers = mock.history.get.filter((req) =>
      req.url?.startsWith('/users'),
    );
    expect(callsToUsers).toHaveLength(0);
  });

  it('REVIEWER로_진입_시_users_API_호출됨', async () => {
    setRole('REVIEWER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 999 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 100 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      // REVIEWER 진입 시 /users 가 최소 1회 이상 호출되어야 한다 (작업자 + 검수자 목록).
      const callsToUsers = mock.history.get.filter(
        (req) => req.url === '/users',
      );
      expect(callsToUsers.length).toBeGreaterThanOrEqual(1);
    });
  });
});
