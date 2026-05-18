import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { TaskListPage } from '@/pages/TaskListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

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
    navigateMock.mockReset();
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
            firstSrcSn: 12345,
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

  it('WORKER가_작업_버튼_클릭_시_firstSrcSn_경로로_navigate', async () => {
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
            firstSrcSn: 12345,
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

    const user = userEvent.setup();
    await user.click(
      screen.getByRole('button', { name: /작업 시작 CCTV-WORK-42/ }),
    );

    await waitFor(() => {
      // videoId(42) 가 아닌 firstSrcSn(12345) 으로 navigate 되어야 한다.
      expect(navigateMock).toHaveBeenCalledWith('/label/12345');
    });
  });

  it('WORKER_시점_firstSrcSn_없으면_작업_버튼_disabled', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 100,
            videoId: 42,
            cctvName: 'CCTV-NO-FRAME',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
            // firstSrcSn 미제공 — 프레임 아직 안 만들어진 영상
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
      expect(screen.getByText('CCTV-NO-FRAME')).toBeInTheDocument();
    });

    const btn = screen.getByRole('button', { name: /작업 시작 CCTV-NO-FRAME/ });
    expect(btn).toBeDisabled();
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

  it('WORKER_TaskListPage_렌더_시_useVideos_미호출', async () => {
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
            eventName: 'FIRE',
            eventTypeCd: 'FIRE',
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
    // /videos 가 호출되면 403 — WORKER 시각에서는 호출 자체가 일어나선 안 됨.
    mock.onGet('/videos').reply(403);

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-1')).toBeInTheDocument();
    });

    const callsToVideos = mock.history.get.filter((req) =>
      req.url?.startsWith('/videos'),
    );
    expect(callsToVideos).toHaveLength(0);
  });

  it('WORKER_taskParams_size_20으로_페이징_호출', async () => {
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

    // WORKER 진입 시 /assignments 요청 size 가 20 이어야 한다 (size=999 금지).
    const assignReq = mock.history.get.find((r) => r.url === '/assignments');
    expect(assignReq).toBeDefined();
    expect(assignReq?.params?.size).toBe(20);
    expect(assignReq?.params?.page).toBe(0);
  });

  it('WORKER_페이지네이션_UI_노출', async () => {
    setRole('WORKER');
    // BE 가 totalPages=3 으로 응답하면 페이지 버튼이 노출되어야 한다.
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 100,
            videoId: 1,
            cctvName: 'CCTV-PG-1',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
          },
        ],
        totalElements: 60,
        totalPages: 3,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-PG-1')).toBeInTheDocument();
    });

    // 다음 페이지 버튼 노출
    expect(
      screen.getByRole('button', { name: '다음 페이지' }),
    ).toBeInTheDocument();
  });

  it('BE_eventName_미응답_시_이벤트_컬럼_dash_폴백', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 100,
            videoId: 1,
            cctvName: 'CCTV-NO-EVT',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
            // eventName/eventTypeCd 미제공
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
      expect(screen.getByText('CCTV-NO-EVT')).toBeInTheDocument();
    });

    // 이벤트 컬럼이 "-" (대시) 로 폴백되어야 한다.
    // EmptyState 등에서 "-" 텍스트가 우연히 매칭될 수 있으므로 row 내부에서만 검증.
    const row = screen.getByText('CCTV-NO-EVT').closest('tr');
    expect(row).not.toBeNull();
    expect(row!.textContent).toContain('-');
  });

  it('WORKER_시각_이벤트_필터_옵션이_tasks의_eventName으로_채워짐', async () => {
    // 이슈 #3: WORKER 시각은 useVideos 비활성으로 videos 가 비어 있어
    // 기존 코드는 eventTypeOptions 가 항상 [] 였다. 본 테스트는 tasks 의 eventName 으로
    // 이벤트 select 옵션이 채워지는지 검증한다.
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 100,
            videoId: 1,
            cctvName: 'CCTV-FIRE-1',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
            eventName: 'FIRE',
            eventTypeCd: 'FIRE',
          },
          {
            id: 101,
            videoId: 2,
            cctvName: 'CCTV-INTRUSION-2',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
            eventName: 'INTRUSION',
            eventTypeCd: 'INTRUSION',
          },
          {
            // 중복 eventName — Set 으로 unique 처리되어야 한다.
            id: 102,
            videoId: 3,
            cctvName: 'CCTV-FIRE-3',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
            eventName: 'FIRE',
            eventTypeCd: 'FIRE',
          },
        ],
        totalElements: 3,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-FIRE-1')).toBeInTheDocument();
    });

    // 이벤트 select 의 옵션 — '전체' + FIRE/INTRUSION (총 3개, 중복 제거됨).
    const eventSelect = screen.getByLabelText('이벤트') as HTMLSelectElement;
    const optionValues = Array.from(eventSelect.options).map((o) => o.value);
    expect(optionValues).toContain('FIRE');
    expect(optionValues).toContain('INTRUSION');
    // 중복 제거 검증 — FIRE 는 한 번만 등장.
    expect(optionValues.filter((v) => v === 'FIRE')).toHaveLength(1);
    // 빈 '전체' 옵션 포함 총 3개.
    expect(optionValues).toHaveLength(3);
  });

  it('REVIEWER로_진입_시_users_API_호출됨', async () => {
    setRole('REVIEWER');
    mock.onGet('/tasks/board').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
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

  it('REVIEWER_진입_시_useVideos_미호출_v1_tasks_board만_호출', async () => {
    // Phase 3: REVIEWER 시각에서 /videos 와 /assignments 가 아니라 /tasks/board 단일 엔드포인트만 호출되어야 한다.
    setRole('REVIEWER');
    mock.onGet('/tasks/board').reply(200, {
      success: true,
      data: {
        content: [
          {
            videoId: 10,
            cctvName: 'CCTV-BOARD-1',
            eventName: 'FIRE',
            eventTypeCd: 'FIRE',
            frameCount: 30,
            capturedAt: '2026-05-07T10:00:00Z',
            batchStatus: 'COMPLETED',
            status: 'COMPLETED',
            assignmentId: 99,
            workerId: 7,
            workerName: '홍길동',
            assignedAt: '2026-05-07T10:00:00Z',
            firstSrcSn: 12345,
            reviewerId: 1,
            reviewerName: '검수자1',
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
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 100 },
      message: null,
      errorCode: null,
    });
    // /videos 가 호출되면 fail — REVIEWER 시각에서도 호출이 일어나선 안 됨.
    mock.onGet('/videos').reply(500);

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD-1')).toBeInTheDocument();
    });

    const callsToVideos = mock.history.get.filter((req) =>
      req.url?.startsWith('/videos'),
    );
    expect(callsToVideos).toHaveLength(0);

    // /tasks/board 가 정확히 1회 이상 호출되어야 한다.
    const callsToBoard = mock.history.get.filter(
      (req) => req.url === '/tasks/board',
    );
    expect(callsToBoard.length).toBeGreaterThanOrEqual(1);
    expect(callsToBoard[0].params?.size).toBe(20);
    expect(callsToBoard[0].params?.status).toBe('COMPLETED');
  });

  it('REVIEWER_시각_미배정_영상도_노출_workerName_미배정_표시', async () => {
    setRole('REVIEWER');
    mock.onGet('/tasks/board').reply(200, {
      success: true,
      data: {
        content: [
          {
            videoId: 20,
            cctvName: 'CCTV-UNASSIGN',
            eventName: 'INTRUSION',
            eventTypeCd: 'INTRUSION',
            frameCount: 0,
            capturedAt: '2026-05-07T10:00:00Z',
            batchStatus: 'COMPLETED',
            status: 'UNASSIGNED',
            assignmentId: null,
            workerId: null,
            workerName: null,
            assignedAt: null,
            firstSrcSn: null,
            reviewerId: null,
            reviewerName: null,
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
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 100 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-UNASSIGN')).toBeInTheDocument();
    });

    // 작업자 컬럼이 "미배정" 으로 표시되어야 한다.
    const row = screen.getByText('CCTV-UNASSIGN').closest('tr');
    expect(row).not.toBeNull();
    expect(row!.textContent).toContain('미배정');

    // 미배정 영상이면 REVIEWER 가 "배정" 버튼을 볼 수 있어야 한다.
    expect(
      screen.getByRole('button', { name: /배정/ }),
    ).toBeInTheDocument();
  });

  it('REVIEWER_페이지네이션_UI_노출_BE_totalPages_기반', async () => {
    setRole('REVIEWER');
    mock.onGet('/tasks/board').reply(200, {
      success: true,
      data: {
        content: [
          {
            videoId: 30,
            cctvName: 'CCTV-PG-REV',
            eventName: null,
            eventTypeCd: null,
            frameCount: 0,
            capturedAt: '2026-05-07T10:00:00Z',
            batchStatus: 'COMPLETED',
            status: 'UNASSIGNED',
            assignmentId: null,
            workerId: null,
            workerName: null,
            assignedAt: null,
            firstSrcSn: null,
            reviewerId: null,
            reviewerName: null,
          },
        ],
        totalElements: 60,
        totalPages: 3,
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
      expect(screen.getByText('CCTV-PG-REV')).toBeInTheDocument();
    });

    // BE totalPages=3 에 따라 페이지네이션 UI 가 노출되어야 한다.
    expect(
      screen.getByRole('button', { name: '다음 페이지' }),
    ).toBeInTheDocument();
  });

  it('REVIEWER_시각_useTasks_미호출_assignments_API_요청_0회', async () => {
    // REVIEWER 시각은 useTaskBoard 만 사용하므로 useTasks 가 게이트되어
    // /assignments 호출이 일어나지 않아야 한다.
    setRole('REVIEWER');
    mock.onGet('/tasks/board').reply(200, {
      success: true,
      data: {
        content: [
          {
            videoId: 10,
            cctvName: 'CCTV-REV-NOTASK',
            eventName: 'FIRE',
            eventTypeCd: 'FIRE',
            frameCount: 5,
            capturedAt: '2026-05-07T10:00:00Z',
            batchStatus: 'COMPLETED',
            status: 'UNASSIGNED',
            assignmentId: null,
            workerId: null,
            workerName: null,
            assignedAt: null,
            firstSrcSn: null,
            reviewerId: null,
            reviewerName: null,
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
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 100 },
      message: null,
      errorCode: null,
    });
    // /assignments 가 호출되면 fail — REVIEWER 시각에서는 호출 자체가 일어나선 안 됨.
    mock.onGet('/assignments').reply(500);

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-REV-NOTASK')).toBeInTheDocument();
    });

    const callsToAssignments = mock.history.get.filter(
      (req) => req.url === '/assignments',
    );
    expect(callsToAssignments).toHaveLength(0);
  });
});
