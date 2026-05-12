import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

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

  it('WORKER_시점_각_row에_작업_이력_버튼_노출', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 100,
            videoId: 42,
            cctvName: 'CCTV-WORK-42',
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

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-WORK-42')).toBeInTheDocument();
    });

    // WORKER 시점에는 작업/이력 버튼이 노출되어야 한다.
    expect(
      screen.getByRole('button', { name: /작업 시작 CCTV-WORK-42/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /배정 이력 보기/ }),
    ).toBeInTheDocument();
    // 배정/재배정 버튼은 노출되지 않아야 한다.
    expect(screen.queryByRole('button', { name: '재배정' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '배정' })).not.toBeInTheDocument();
  });

  it('WORKER가_이력_버튼_클릭_시_HistoryDrawer가_열림', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 100,
            videoId: 42,
            cctvName: 'CCTV-WORK-42',
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
    mock.onGet('/assignments/100/history').reply(200, {
      success: true,
      data: [
        {
          eventSeq: 1,
          eventTypeCd: 'ASSIGN',
          actorUserNo: 1,
          actorUserName: '검수자1',
          subjectUserNo: 7,
          subjectUserName: '홍길동',
          prevUserNo: null,
          prevUserName: null,
          reason: null,
          occurredAt: '2026-05-07T10:00:00Z',
        },
      ],
      message: null,
      errorCode: null,
    });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-WORK-42')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /배정 이력 보기/ }));

    await waitFor(() => {
      // Drawer 헤더에 '배정 이력' 텍스트가 렌더링 됨
      expect(screen.getByRole('dialog', { name: '배정 이력' })).toBeInTheDocument();
    });
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
