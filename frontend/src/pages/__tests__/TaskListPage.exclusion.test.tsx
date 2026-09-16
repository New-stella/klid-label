/**
 * 작업 목록 — 제외분 배제·전환 축과 배정 해제 회귀 가드.
 * [@design SCREEN-012] [@design API-136] [@design API-259]
 * [@design AC-1123] [@design AC-1124] [@design AC-1126] [@design AC-1127]
 */
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
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    token: 'dummy-jwt',
    claims: { sub: 'u-7', role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

function ok<T>(data: T) {
  return { success: true, data, message: null, errorCode: null };
}

interface BoardRow {
  videoId: number;
  cctvName: string;
  status: string;
  assignmentId?: number | null;
  workerId?: number | null;
  workerName?: string | null;
}

function mockBoard(mock: MockAdapter, rows: BoardRow[], excludedCount = 0) {
  mock.onGet('/tasks/board').reply(200,
    ok({
      content: rows.map((r) => ({
        eventName: '',
        eventTypeCd: '',
        frameCount: 0,
        capturedAt: '2026-05-07T10:00:00Z',
        batchStatus: 'COMPLETED',
        assignedAt: '2026-05-07T11:00:00Z',
        firstSrcSn: null,
        assignmentId: null,
        workerId: null,
        workerName: null,
        ...r,
      })),
      totalElements: rows.length,
      totalPages: 1,
      number: 0,
      size: 20,
    }),
  );
  // ★「제외됨 N건」은 **집계 창구**가 싣는다(목록 조회는 싣지 않는다).
  mock.onGet('/tasks/board/summary').reply(200,
    ok({
      total: rows.length,
      unassigned: 0,
      inProgress: rows.length,
      reviewPending: 0,
      completed: 0,
      rejected: 0,
      excludedCount,
    }),
  );
  mock.onGet('/tasks/board/event-types').reply(200, ok({ items: [], truncated: false }));
  mock.onGet('/users').reply(200,
    ok({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 100 }),
  );
}

/** 마지막 목록 요청의 질의 항목 — 「무엇을 빼고 보냈는가」를 본다. */
function lastBoardParams(mock: MockAdapter): Record<string, unknown> {
  const calls = mock.history.get.filter((c) => c.url === '/tasks/board');
  return (calls.at(-1)?.params ?? {}) as Record<string, unknown>;
}

const ASSIGNED = {
  videoId: 10,
  cctvName: 'CCTV-배정됨',
  assignmentId: 501,
  workerId: 7,
  workerName: '김작업',
};

describe('작업 목록 — 제외분 배제와 배정 해제', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.clearAllMocks();
  });

  // ── 「제외됨 N건」 ────────────────────────────────────────────────

  it('★제외됨이_0건이어도_표시된다', async () => {
    setRole('REVIEWER');
    mockBoard(mock, [{ ...ASSIGNED, status: 'IN_PROGRESS' }], 0);

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    // ★★**집계가 도착한 뒤**를 관측한다. 이 표시는 첫 렌더부터 자리에 있고 그때는 아직 값을
    //   못 받아 「제외됨 0건」으로 그려진다 — 기다리지 않으면 이 케이스는 **서버 값을 한 번도
    //   읽지 않고도 통과**한다. 값을 받았는지는 버튼이 눌리는지로 가른다.
    const toggle = await screen.findByTestId('excluded-count-toggle');
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).toHaveTextContent('제외됨 0건');
  });

  it('★제외됨을_누르면_작업_진행_상태_축만_빼고_전환한다', async () => {
    // 그 숫자를 주는 집계 창구가 상태 축을 반영하지 않고 세므로, 상태를 그대로 둔 채 전환하면
    // 결과가 누른 숫자보다 적어진다. 나머지 필터는 그대로 가야 결과가 넓어지지도 않는다.
    setRole('REVIEWER');
    mockBoard(mock, [{ ...ASSIGNED, status: 'IN_PROGRESS' }], 2);

    renderWithProviders(<TaskListPage />, {
      initialEntries: ['/task?status=REJECTED&q=강남&eventTypeCd=010001'],
    });

    await waitFor(() => expect(lastBoardParams(mock).workStatus).toBe('REJECTED'));

    await userEvent.click(screen.getByTestId('excluded-count-toggle'));

    await waitFor(() => expect(lastBoardParams(mock).excludedOnly).toBe(true));
    const params = lastBoardParams(mock);
    expect(params.workStatus).toBeUndefined();
    expect(params.q).toBe('강남');
    expect(params.eventTypeCd).toBe('010001');
  });

  it('★작업자_축에는_제외됨_표시가_없다', async () => {
    // 작업자는 제외·복원을 하지 않으며 제외된 영상에는 배정이 없어 그 목록에 나타날 일도 없다.
    setRole('WORKER');
    mock.onGet('/assignments').reply(200,
      ok({ content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 }),
    );
    mock.onGet('/assignments/event-types').reply(200, ok({ items: [], truncated: false }));

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await screen.findByText('작업 목록');

    expect(screen.queryByTestId('excluded-count-toggle')).not.toBeInTheDocument();
  });

  // ── 배정 해제 ────────────────────────────────────────────────────

  it('배정된_행에_배정_해제_버튼이_재배정과_같은_자리에_있다', async () => {
    setRole('REVIEWER');
    mockBoard(mock, [{ ...ASSIGNED, status: 'IN_PROGRESS' }], 0);

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    const unassign = await screen.findByTestId('task-unassign-10');
    expect(unassign).toBeEnabled();
    // 재배정과 같은 자리(같은 액션 칸)에 놓인다 — 뜻은 다르지만 자리는 같다.
    expect(unassign.closest('td')).toBe(
      screen.getByRole('button', { name: '재배정' }).closest('td'),
    );
  });

  it.each([
    ['REVIEW_PENDING', '검수 대기·검수 중'],
    ['COMPLETED', '승인'],
  ])('★검수_단계_배정(%s)은_해제_버튼이_미리_비활성되고_사유가_보인다', async (status) => {
    setRole('REVIEWER');
    mockBoard(mock, [{ ...ASSIGNED, status }], 0);

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    expect(await screen.findByTestId('task-unassign-10')).toBeDisabled();
    expect(screen.getByTestId('task-unassign-blocked-10')).toHaveTextContent(
      '검수에 들어간 배정은 해제할 수 없습니다.',
    );
  });

  it('★★반려_배정은_해제_버튼이_활성이다', async () => {
    // 반려는 워크플로가 작업자에게 되돌아온 상태라 그 작업 자체를 접을 수 있어야 한다.
    // 「반려도 검수 축이니 함께 막자」로 넓히면 **「반려 → 해제 → 제외」 경로가 통째로 막힌다**.
    setRole('REVIEWER');
    mockBoard(mock, [{ ...ASSIGNED, status: 'REJECTED' }], 0);

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    expect(await screen.findByTestId('task-unassign-10')).toBeEnabled();
    expect(screen.queryByTestId('task-unassign-blocked-10')).not.toBeInTheDocument();
  });

  it('해제는_확인_단계를_거치고_사유를_받지_않는다', async () => {
    setRole('REVIEWER');
    mockBoard(mock, [{ ...ASSIGNED, status: 'IN_PROGRESS' }], 0);
    mock.onDelete('/assignments/501').reply(204);

    const user = userEvent.setup();
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await user.click(await screen.findByTestId('task-unassign-10'));

    const dialog = await screen.findByTestId('release-confirm-dialog');
    // 사유 입력 칸이 없다 — 「감추는 쪽만 사유를 남긴다」.
    expect(dialog.querySelector('textarea')).toBeNull();
    // 배정만 푼다는 것과 되돌리는 방법을 함께 알린다.
    expect(screen.getByTestId('release-keeps-labels-notice')).toHaveTextContent(
      '해제해도 라벨과 그 이력은 남습니다.',
    );

    await user.click(screen.getByTestId('release-confirm'));
    await waitFor(() => {
      expect(mock.history.delete.filter((c) => c.url === '/assignments/501')).toHaveLength(1);
    });
  });

  // ── 제외분만 보기 ─────────────────────────────────────────────────

  it('★제외분_보기에서는_배정_재배정_해제와_선택_수단이_모두_사라진다', async () => {
    setRole('REVIEWER');
    mockBoard(mock, [{ ...ASSIGNED, status: 'IN_PROGRESS' }], 1);

    renderWithProviders(<TaskListPage />, {
      initialEntries: ['/task?excludedOnly=true'],
    });

    await screen.findByText('CCTV-배정됨');

    expect(screen.queryByRole('button', { name: '재배정' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '배정' })).not.toBeInTheDocument();
    expect(screen.queryByTestId('task-unassign-10')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('CCTV-배정됨 선택')).not.toBeInTheDocument();
    expect(screen.queryByTestId('bulk-assign-bar')).not.toBeInTheDocument();
  });

  it('★이_화면에는_제외_복원_동작이_없다', async () => {
    // 제외·복원을 수행하는 자리는 영상 처리 현황 하나다(AC-1126).
    setRole('REVIEWER');
    mockBoard(mock, [{ ...ASSIGNED, status: 'IN_PROGRESS' }], 1);

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task?excludedOnly=true'] });
    await screen.findByText('CCTV-배정됨');

    expect(screen.queryByRole('button', { name: /^제외$/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /복원/ })).not.toBeInTheDocument();
  });
});
