// SCR-TASK-002 배정 전용 페이지 — TDD RED 단계.
//
// REVIEWER 전용 배정 화면:
// - 미배정 영상 목록 (TaskBoard API, status=UNASSIGNED 필터)
// - 각 영상에 WORKER 선택 + 배정 버튼
// - POST /v1/assignments 호출
//
// given/when/then 구조 필수.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

import { TaskAssignPage } from '@/pages/TaskAssignPage';

function mockTaskBoard(mock: MockAdapter) {
  mock.onGet('/tasks/board').reply(200, {
    success: true,
    data: {
      content: [
        {
          videoId: 10,
          cctvName: 'CCTV-A',
          eventName: '교통사고',
          eventTypeCd: 'ACCIDENT',
          frameCount: 30,
          capturedAt: '2026-05-01T10:00:00Z',
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
        {
          videoId: 20,
          cctvName: 'CCTV-B',
          eventName: null,
          eventTypeCd: null,
          frameCount: 15,
          capturedAt: '2026-05-02T08:00:00Z',
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
      totalElements: 2,
      totalPages: 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  });
}

function mockWorkers(mock: MockAdapter) {
  mock.onGet('/users/workers').reply(200, {
    success: true,
    data: [
      { id: 7, name: '홍길동', active: true },
      { id: 8, name: '김작업', active: true },
    ],
    message: null,
    errorCode: null,
  });
}

function mockReviewers(mock: MockAdapter) {
  mock.onGet('/users').reply(200, {
    success: true,
    data: {
      content: [{ id: 1, name: '관리자', email: 'admin@test.com', role: 'REVIEWER', active: true }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 50,
    },
    message: null,
    errorCode: null,
  });
}

describe('TaskAssignPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // given: REVIEWER 로그인 상태
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999, name: '관리자' },
    });
    mockReviewers(mock);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('배정_페이지_미배정_영상_목록_렌더링', async () => {
    // given: 미배정 영상 2건이 TaskBoard에 존재
    mockTaskBoard(mock);
    mockWorkers(mock);

    // when: 배정 전용 페이지 렌더링
    renderWithProviders(<TaskAssignPage />, {
      initialEntries: ['/task/assign'],
      routes: [{ path: '/task/assign', element: <TaskAssignPage /> }],
    });

    // then: 미배정 영상 2건이 목록에 표시됨
    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });
    expect(screen.getByText('CCTV-B')).toBeInTheDocument();
  });

  it('WORKER_선택_후_배정_버튼_클릭시_API_호출', async () => {
    // given: 미배정 영상 목록 + 작업자 목록 로드됨
    mockTaskBoard(mock);
    mockWorkers(mock);

    let postBody: unknown;
    mock.onPost('/assignments').reply((config) => {
      postBody = JSON.parse(config.data ?? '{}');
      return [
        201,
        {
          success: true,
          data: {
            id: 200,
            videoId: 10,
            workerId: 7,
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();

    // when: 페이지 렌더링
    renderWithProviders(<TaskAssignPage />, {
      initialEntries: ['/task/assign'],
      routes: [{ path: '/task/assign', element: <TaskAssignPage /> }],
    });

    // then: 영상 목록이 로드되면 배정 버튼 클릭 가능
    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });

    // when: 첫 번째 영상의 배정 버튼 클릭
    const assignButtons = screen.getAllByRole('button', { name: /배정/ });
    await user.click(assignButtons[0]);

    // then: 모달이 열리고 작업자를 선택
    await waitFor(() => {
      expect(screen.getByLabelText(/작업자/)).toBeInTheDocument();
    });

    const select = screen.getByLabelText(/작업자/) as HTMLSelectElement;
    await user.selectOptions(select, '7');

    // when: 저장 버튼 클릭
    const submitBtn = screen.getByRole('button', { name: '저장' });
    await user.click(submitBtn);

    // then: POST /assignments 호출됨
    await waitFor(() => {
      expect(postBody).toMatchObject({ workerId: 7, rawDataIds: [10] });
    });
  });

  it('작업자_미선택시_배정_버튼_비활성', async () => {
    // given: 미배정 영상 목록 로드됨
    mockTaskBoard(mock);
    mockWorkers(mock);

    const user = userEvent.setup();

    // when: 배정 페이지 렌더
    renderWithProviders(<TaskAssignPage />, {
      initialEntries: ['/task/assign'],
      routes: [{ path: '/task/assign', element: <TaskAssignPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });

    // when: 배정 버튼 클릭하여 모달 오픈
    const assignButtons = screen.getAllByRole('button', { name: /배정/ });
    await user.click(assignButtons[0]);

    // then: 모달이 열리면 작업자 미선택 상태에서 저장 버튼 비활성
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    });
  });

  it('I3_미배정_목록_API_오류시_빈상태가_아니라_오류_메시지와_재시도_노출', async () => {
    // given: TaskBoard API 가 500 으로 실패 (이전엔 빈 상태로 조용히 표시됨)
    mock.onGet('/tasks/board').reply(500, {
      success: false,
      data: null,
      message: '서버 오류',
      errorCode: 'INTERNAL_ERROR',
    });
    mockWorkers(mock);

    // when: 배정 페이지 렌더
    renderWithProviders(<TaskAssignPage />, {
      initialEntries: ['/task/assign'],
      routes: [{ path: '/task/assign', element: <TaskAssignPage /> }],
    });

    // then: 빈 상태가 아니라 로드 실패 메시지 + 재시도 버튼 노출
    await waitFor(() => {
      expect(screen.getByTestId('assign-page-error')).toBeInTheDocument();
    });
    expect(screen.getByText('목록을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '재시도' })).toBeInTheDocument();
    expect(screen.queryByTestId('assign-page-empty')).not.toBeInTheDocument();
  });

  it('I1_미배정_목록에_상태무관_영상이_배치상태_뱃지와_함께_표시', async () => {
    // given: PENDING / FAILED / COMPLETED 미배정 영상 (배치 상태 무관 노출)
    mock.onGet('/tasks/board').reply(200, {
      success: true,
      data: {
        content: [
          {
            videoId: 31,
            cctvName: 'CCTV-PENDING',
            eventName: null,
            eventTypeCd: null,
            frameCount: 0,
            capturedAt: '2026-06-01T10:00:00Z',
            batchStatus: 'PENDING',
            status: 'UNASSIGNED',
            assignmentId: null,
            workerId: null,
            workerName: null,
            assignedAt: null,
            firstSrcSn: null,
            reviewerId: null,
            reviewerName: null,
          },
          {
            videoId: 32,
            cctvName: 'CCTV-FAILED',
            eventName: null,
            eventTypeCd: null,
            frameCount: 0,
            capturedAt: '2026-06-01T11:00:00Z',
            batchStatus: 'FAILED',
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
        totalElements: 2,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
    mockWorkers(mock);

    // when: 배정 페이지 렌더
    renderWithProviders(<TaskAssignPage />, {
      initialEntries: ['/task/assign'],
      routes: [{ path: '/task/assign', element: <TaskAssignPage /> }],
    });

    // then: 각 행에 배치 상태 뱃지가 표시됨
    await waitFor(() => {
      expect(screen.getByTestId('assign-status-31')).toHaveTextContent('PENDING');
    });
    expect(screen.getByTestId('assign-status-32')).toHaveTextContent('FAILED');
  });

  it('미배정_영상이_없으면_빈_상태_안내_표시', async () => {
    // given: TaskBoard 응답이 빈 목록
    mock.onGet('/tasks/board').reply(200, {
      success: true,
      data: {
        content: [],
        totalElements: 0,
        totalPages: 0,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
    mockWorkers(mock);

    // when: 배정 페이지 렌더
    renderWithProviders(<TaskAssignPage />, {
      initialEntries: ['/task/assign'],
      routes: [{ path: '/task/assign', element: <TaskAssignPage /> }],
    });

    // then: 빈 상태 안내 표시
    await waitFor(() => {
      expect(screen.getByTestId('assign-page-empty')).toBeInTheDocument();
    });
  });

  it('일괄_배정_체크박스_선택_후_일괄_배정_버튼_API_호출', async () => {
    // given: 미배정 영상 2건
    mockTaskBoard(mock);
    mockWorkers(mock);

    let postBody: unknown;
    mock.onPost('/assignments').reply((config) => {
      postBody = JSON.parse(config.data ?? '{}');
      return [
        201,
        {
          success: true,
          data: { id: 300, videoId: 10, workerId: 7, status: 'PENDING', assignedAt: '2026-05-07T10:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();

    // when: 배정 페이지 렌더
    renderWithProviders(<TaskAssignPage />, {
      initialEntries: ['/task/assign'],
      routes: [{ path: '/task/assign', element: <TaskAssignPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });

    // when: 체크박스로 2건 선택
    const checkboxes = screen.getAllByRole('checkbox');
    // 첫 번째는 전체선택, 나머지가 개별 행
    await user.click(checkboxes[1]); // CCTV-A
    await user.click(checkboxes[2]); // CCTV-B

    // when: 페이지 상단 일괄 배정 버튼 클릭
    const bulkBtns = screen.getAllByRole('button', { name: /일괄 배정/ });
    await user.click(bulkBtns[0]);

    // then: 모달에서 작업자 선택 후 저장
    await waitFor(() => {
      expect(screen.getByLabelText(/작업자/)).toBeInTheDocument();
    });

    const select = screen.getByLabelText(/작업자/) as HTMLSelectElement;
    await user.selectOptions(select, '7');

    // 모달의 제출 버튼: "2건 일괄 배정"
    const modalBtns = screen.getAllByRole('button', { name: /일괄 배정/ });
    // 모달 footer 버튼은 마지막에 위치
    await user.click(modalBtns[modalBtns.length - 1]);

    // then: POST /assignments 호출 — rawDataIds 에 2건 포함
    await waitFor(() => {
      expect(postBody).toMatchObject({ workerId: 7, rawDataIds: [10, 20] });
    });
  });
});
