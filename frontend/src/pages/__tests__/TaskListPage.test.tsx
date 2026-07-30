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
    // REVIEWER 시각 진입 시 함께 호출되는 부수 엔드포인트 기본 스텁.
    // 스텁이 없으면 mock adapter 가 passthrough 로 **실제 네트워크**를 시도해 테스트가 플레이키해진다.
    // (`/tasks/board` 는 각 테스트가 직접 스텁하므로 여기서 등록하지 않는다 — 먼저 등록된 핸들러가 이긴다.)
    mock.onGet('/tasks/board/summary').reply(200, {
      success: true,
      data: {
        total: 0,
        unassigned: 0,
        inProgress: 0,
        reviewPending: 0,
        completed: 0,
        rejected: 0,
      },
      message: null,
      errorCode: null,
    });
    mock.onGet('/tasks/board/event-types').reply(200, {
      success: true,
      data: { items: [], truncated: false },
      message: null,
      errorCode: null,
    });
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

  it('WORKER_시점_firstSrcSn_없으면_마킹_버튼_표시_작업_버튼_없음', async () => {
    // V2.0 변경: firstSrcSn 미존재 시 disabled 작업 버튼 대신 "마킹" 버튼으로 대체
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

    // "마킹" 버튼이 표시되어야 한다 (disabled "작업" 대신)
    expect(
      screen.getByRole('button', { name: /마킹 시작 CCTV-NO-FRAME/ }),
    ).toBeInTheDocument();
    // "작업" 버튼은 존재하지 않아야 한다
    expect(
      screen.queryByRole('button', { name: /작업 시작 CCTV-NO-FRAME/ }),
    ).not.toBeInTheDocument();
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
    // (KPI '미배정' 카드도 버튼이므로 정확한 이름으로 찾는다.)
    expect(screen.getByRole('button', { name: '배정' })).toBeInTheDocument();
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

  // --- 마킹 네비게이션 + 버튼 분기 ---

  it('WORKER_프레임없는_영상_마킹_버튼_렌더링', async () => {
    // given: firstSrcSn 이 없는 배정 (프레임 미생성 영상)
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 200,
            videoId: 50,
            cctvName: 'CCTV-NO-FRAME-50',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-27T10:00:00Z',
            // firstSrcSn 미제공 — 프레임 아직 미생성
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

    // when
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    // then: "마킹" 버튼이 렌더링되어야 한다
    await waitFor(() => {
      expect(screen.getByText('CCTV-NO-FRAME-50')).toBeInTheDocument();
    });
    expect(
      screen.getByRole('button', { name: /마킹 시작 CCTV-NO-FRAME-50/ }),
    ).toBeInTheDocument();
    // "작업" 버튼은 없어야 한다
    expect(
      screen.queryByRole('button', { name: /작업 시작 CCTV-NO-FRAME-50/ }),
    ).not.toBeInTheDocument();
  });

  it('WORKER_마킹_버튼_클릭_시_marking_경로로_navigate', async () => {
    // given: firstSrcSn 없는 배정
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 200,
            videoId: 50,
            cctvName: 'CCTV-MARK-50',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-27T10:00:00Z',
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
      expect(screen.getByText('CCTV-MARK-50')).toBeInTheDocument();
    });

    // when: 마킹 버튼 클릭
    const user = userEvent.setup();
    await user.click(
      screen.getByRole('button', { name: /마킹 시작 CCTV-MARK-50/ }),
    );

    // then: /marking/{videoId} 로 navigate
    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/marking/50');
    });
  });

  it('WORKER_프레임있는_영상_작업_버튼만_렌더링_마킹_버튼_없음', async () => {
    // given: firstSrcSn 존재
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 201,
            videoId: 51,
            cctvName: 'CCTV-HAS-FRAME-51',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-27T10:00:00Z',
            firstSrcSn: 99999,
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

    // when
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    // then: "작업" 버튼만 존재, "마킹" 버튼은 없어야 한다
    await waitFor(() => {
      expect(screen.getByText('CCTV-HAS-FRAME-51')).toBeInTheDocument();
    });
    expect(
      screen.getByRole('button', { name: /작업 시작 CCTV-HAS-FRAME-51/ }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /마킹 시작 CCTV-HAS-FRAME-51/ }),
    ).not.toBeInTheDocument();
  });

  // Phase 3 — 비식별 미완료 영상 마킹 진입 차단 (AC4)
  it('비식별_미완료_영상은_마킹_진입_버튼이_비활성화된다', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 301,
            videoId: 61,
            cctvName: 'CCTV-DEIDENT-PENDING',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-27T10:00:00Z',
            // firstSrcSn 없음 → 마킹 버튼 경로. deIdntfYn 미완료 → 진입 차단.
            deIdntfYn: 'N',
            deidentStatus: 'IN_PROGRESS',
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
      expect(screen.getByText('CCTV-DEIDENT-PENDING')).toBeInTheDocument();
    });

    // 마킹 버튼은 존재하되 비활성화 + 차단 사유가 접근성 라벨에 포함된다.
    const markBtn = screen.getByRole('button', { name: /마킹 불가.*CCTV-DEIDENT-PENDING/ });
    expect(markBtn).toBeDisabled();
    // 클릭해도 navigate 되지 않는다.
    const user = userEvent.setup();
    await user.click(markBtn);
    expect(navigateMock).not.toHaveBeenCalledWith('/marking/61');
  });

  it('비식별_완료_영상은_마킹_화면으로_진입할_수_있다', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 302,
            videoId: 62,
            cctvName: 'CCTV-DEIDENT-DONE',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-27T10:00:00Z',
            // firstSrcSn 없음 → 마킹 버튼 경로. deIdntfYn='Y' → 진입 허용.
            deIdntfYn: 'Y',
            deidentStatus: 'DONE',
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
      expect(screen.getByText('CCTV-DEIDENT-DONE')).toBeInTheDocument();
    });

    const markBtn = screen.getByRole('button', { name: /마킹 시작 CCTV-DEIDENT-DONE/ });
    expect(markBtn).toBeEnabled();

    const user = userEvent.setup();
    await user.click(markBtn);
    expect(navigateMock).toHaveBeenCalledWith('/marking/62');
  });

  // --- 증강 여부/종류 뱃지 (R3 FE) ---

  function replyWorkerTask(overrides: Record<string, unknown>) {
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 400,
            videoId: 70,
            cctvName: 'CCTV-AUG',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-27T10:00:00Z',
            eventName: 'FIRE',
            eventTypeCd: 'FIRE',
            ...overrides,
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
  }

  it('작업목록_augmented항목_증강뱃지_표시', async () => {
    setRole('WORKER');
    replyWorkerTask({ augmented: true, augType: 'WINTER' });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-AUG')).toBeInTheDocument();
    });

    expect(screen.getByTestId('task-aug-badge-70')).toBeInTheDocument();
  });

  it('작업목록_augType_WINTER_겨울라벨', async () => {
    setRole('WORKER');
    replyWorkerTask({ augmented: true, augType: 'WINTER' });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-AUG')).toBeInTheDocument();
    });

    const badge = screen.getByTestId('task-aug-badge-70');
    expect(badge).toHaveTextContent('겨울');
    // 기술모델명/코드 미노출
    expect(badge).not.toHaveTextContent('WINTER');
  });

  it('작업목록_augType_RESL_720P_해상도라벨', async () => {
    setRole('WORKER');
    replyWorkerTask({ augmented: true, augType: 'RESL_720P' });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-AUG')).toBeInTheDocument();
    });

    const badge = screen.getByTestId('task-aug-badge-70');
    expect(badge).toHaveTextContent('해상도 720p');
    expect(badge).not.toHaveTextContent('RESL');
  });

  it('작업목록_augType_null이면_증강뱃지만', async () => {
    setRole('WORKER');
    replyWorkerTask({ augmented: true, augType: null });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-AUG')).toBeInTheDocument();
    });

    const badge = screen.getByTestId('task-aug-badge-70');
    expect(badge).toHaveTextContent('증강');
    // 접근성 — 뱃지에 aria-label 존재
    expect(badge).toHaveAttribute('aria-label');
  });

  it('원본항목_뱃지_미표시', async () => {
    setRole('WORKER');
    replyWorkerTask({ augmented: false });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-AUG')).toBeInTheDocument();
    });

    // 원본(augmented=false) 은 증강 뱃지가 없어야 한다.
    expect(screen.queryByTestId('task-aug-badge-70')).not.toBeInTheDocument();
  });

  it('REVIEWER_보드항목_augmented_증강뱃지_표시', async () => {
    setRole('REVIEWER');
    mock.onGet('/tasks/board').reply(200, {
      success: true,
      data: {
        content: [
          {
            videoId: 80,
            cctvName: 'CCTV-REV-AUG',
            eventName: 'FIRE',
            eventTypeCd: 'FIRE',
            frameCount: 30,
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
            augmented: true,
            augType: 'RAIN',
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
      expect(screen.getByText('CCTV-REV-AUG')).toBeInTheDocument();
    });

    const badge = screen.getByTestId('task-aug-badge-80');
    expect(badge).toHaveTextContent('비');
  });
});
