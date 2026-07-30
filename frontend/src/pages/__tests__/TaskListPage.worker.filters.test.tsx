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
  return { ...actual, useNavigate: () => navigateMock };
});

function setRole(role: 'REVIEWER' | 'WORKER', sub = 'u-7') {
  useAuthStore.setState({
    token: 'dummy-jwt',
    claims: { sub, role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

interface TaskOverrides {
  id?: number;
  videoId?: number;
  cctvName?: string;
  status?: string;
  eventTypeCd?: string;
}

function task(o: TaskOverrides = {}) {
  return {
    id: o.id ?? 100,
    videoId: o.videoId ?? 1,
    cctvName: o.cctvName ?? 'CCTV-W1',
    workerId: 7,
    workerName: '홍길동',
    status: o.status ?? 'PENDING',
    assignedAt: '2026-05-07T10:00:00Z',
    eventName: o.eventTypeCd ?? 'EV01',
    eventTypeCd: o.eventTypeCd ?? 'EV01',
  };
}

function page<T>(content: T[], totalPages = 1, totalElements = content.length) {
  return {
    success: true,
    data: { content, totalElements, totalPages, number: 0, size: 20 },
    message: null,
    errorCode: null,
  };
}

function ok<T>(data: T) {
  return { success: true, data, message: null, errorCode: null };
}

/** 목록 요청 목록(호출 횟수 단언용) — `/assignments` **정확 일치**만 센다. */
function assignmentCalls(mock: MockAdapter) {
  return mock.history.get.filter((r) => r.url === '/assignments');
}

function lastAssignmentParams(mock: MockAdapter) {
  const calls = assignmentCalls(mock);
  return calls[calls.length - 1]?.params as Record<string, unknown> | undefined;
}

function optionCalls(mock: MockAdapter) {
  return mock.history.get.filter((r) => r.url === '/assignments/event-types');
}

/**
 * Phase 4 — WORKER 작업목록의 **서버사이드 필터 전환** 검증.
 *
 * 변경 전에는 `/v1/assignments` 가 필터를 지원하지 않아 화면이 **현재 페이지 20건 안에서** 다시
 * 걸렀다. 그래서 뒷페이지 항목은 검색해도 나오지 않았고 "전체 N건"·페이지 수·이벤트 드롭다운이
 * 목록과 다른 집합을 말했다. 이 스펙은 그 재발을 막는다.
 */
describe('TaskListPage — WORKER 서버사이드 필터 (Phase 4)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function stubWorker(options?: {
    rows?: ReturnType<typeof task>[];
    totalPages?: number;
    totalElements?: number;
    eventTypes?: { items: string[]; truncated: boolean };
    eventTypesStatus?: number;
  }) {
    const rows = options?.rows ?? [task()];
    mock
      .onGet('/assignments')
      .reply(
        200,
        page(rows, options?.totalPages ?? 1, options?.totalElements ?? rows.length),
      );
    if (options?.eventTypesStatus) {
      mock.onGet('/assignments/event-types').reply(options.eventTypesStatus);
    } else {
      mock
        .onGet('/assignments/event-types')
        .reply(
          200,
          ok(options?.eventTypes ?? { items: ['EV01', 'EV02'], truncated: false }),
        );
    }
  }

  /** 검색어를 입력하고 "조회" 를 눌러 제출한다. */
  async function submitSearch(user: ReturnType<typeof userEvent.setup>, keyword: string) {
    await user.type(screen.getByLabelText('영상명 / 작업자명'), keyword);
    await user.click(screen.getByRole('button', { name: /조회/ }));
  }

  it('작업목록_검색어_입력시_서버_파라미터로_전송된다', async () => {
    // given
    setRole('WORKER');
    stubWorker();
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-W1')).toBeInTheDocument();
    });

    // when
    const user = userEvent.setup();
    await submitSearch(user, '강남');

    // then: 화면이 아니라 서버가 거른다.
    await waitFor(() => {
      expect(lastAssignmentParams(mock)?.q).toBe('강남');
    });
    // 필터 변경은 항상 첫 페이지부터 다시 본다.
    expect(lastAssignmentParams(mock)?.page).toBe(0);
  });

  it('작업목록_상태_이벤트_필터도_서버_파라미터로_전송된다', async () => {
    // given
    setRole('WORKER');
    stubWorker();
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-W1')).toBeInTheDocument();
    });

    // when
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText('상태'), 'IN_PROGRESS');
    await user.selectOptions(screen.getByLabelText('이벤트'), 'EV02');
    await user.click(screen.getByRole('button', { name: /조회/ }));

    // then: IN_PROGRESS 는 BE allowlist 값이다(board 축과 달리 400 이 아니다).
    await waitFor(() => {
      expect(lastAssignmentParams(mock)?.workStatus).toBe('IN_PROGRESS');
    });
    expect(lastAssignmentParams(mock)?.eventTypeCd).toBe('EV02');
  });

  it('작업목록_클라이언트_재필터가_적용되지_않는다', async () => {
    // given: 서버가 (자기 기준으로 이미 필터한) 2행을 돌려준다.
    setRole('WORKER');
    stubWorker({
      rows: [
        task({ id: 1, videoId: 1, cctvName: 'CCTV-강남', status: 'IN_PROGRESS' }),
        task({ id: 2, videoId: 2, cctvName: 'CCTV-종로', status: 'COMPLETED' }),
      ],
    });
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-강남')).toBeInTheDocument();
    });

    // when: 화면에서 걸러지면 사라졌을 조건으로 검색·상태 필터를 건다.
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText('상태'), 'IN_PROGRESS');
    await submitSearch(user, '강남');
    await waitFor(() => {
      expect(lastAssignmentParams(mock)?.q).toBe('강남');
    });

    // then: 서버 응답 그대로 그린다 — 두 행 모두 남아야 한다.
    expect(screen.getByText('CCTV-강남')).toBeInTheDocument();
    expect(screen.getByText('CCTV-종로')).toBeInTheDocument();
  });

  it('전체건수는_서버_totalElements_를_표시한다', async () => {
    // given: 현재 페이지 1건 / 전체 42건 (필터 결과 전체 기준)
    setRole('WORKER');
    stubWorker({ rows: [task()], totalPages: 3, totalElements: 42 });

    // when
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    // then: 현재 페이지 행 수(1)가 아니라 서버 집계를 보여준다.
    await waitFor(() => {
      expect(screen.getByText(/전체 42건/)).toBeInTheDocument();
    });
  });

  it('필터_적용후에도_전체건수는_서버_값을_따른다', async () => {
    // given
    setRole('WORKER');
    stubWorker({ rows: [task(), task({ id: 2, videoId: 2, cctvName: 'CCTV-W2' })], totalElements: 7 });
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText(/전체 7건/)).toBeInTheDocument();
    });

    // when
    const user = userEvent.setup();
    await submitSearch(user, '강남');
    await waitFor(() => {
      expect(lastAssignmentParams(mock)?.q).toBe('강남');
    });

    // then: 화면 행 수(2)로 덮어쓰지 않는다.
    expect(screen.getByText(/전체 7건/)).toBeInTheDocument();
  });

  it('이벤트유형_옵션은_옵션_API_결과를_사용한다', async () => {
    // given: 현재 페이지에는 EV01 만 있는데 옵션 API 는 전체 기준 3종을 돌려준다.
    setRole('WORKER');
    stubWorker({
      rows: [task({ eventTypeCd: 'EV01' })],
      eventTypes: { items: ['EV01', 'EV02', 'EV03'], truncated: false },
    });

    // when
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-W1')).toBeInTheDocument();
    });

    // then: 뒷페이지에만 있는 코드도 고를 수 있어야 한다.
    const select = screen.getByLabelText('이벤트') as HTMLSelectElement;
    const values = Array.from(select.options).map((o) => o.value);
    expect(values).toEqual(['', 'EV01', 'EV02', 'EV03']);
    expect(optionCalls(mock).length).toBeGreaterThanOrEqual(1);
  });

  it('이벤트유형_옵션_요청에는_다른_축_필터를_보내지_않는다', async () => {
    // given: 옵션이 필터에 따라 좁아지면 사용자가 되돌아갈 수 없다(board 와 같은 규약).
    setRole('WORKER');
    stubWorker();
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-W1')).toBeInTheDocument();
    });

    // when
    const user = userEvent.setup();
    await submitSearch(user, '강남');
    await waitFor(() => {
      expect(lastAssignmentParams(mock)?.q).toBe('강남');
    });

    // then
    optionCalls(mock).forEach((call) => {
      const params = (call.params ?? {}) as Record<string, unknown>;
      expect(params.q).toBeUndefined();
      expect(params.workStatus).toBeUndefined();
      expect(params.eventTypeCd).toBeUndefined();
    });
  });

  it('이벤트유형_옵션이_잘리면_안내를_노출한다', async () => {
    // given: truncated 를 버리면 "그 유형 영상이 없다" 는 조용한 오인이 된다.
    setRole('WORKER');
    stubWorker({ eventTypes: { items: ['EV01'], truncated: true } });

    // when
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    // then
    await waitFor(() => {
      expect(screen.getByTestId('event-type-truncated')).toBeInTheDocument();
    });
  });

  it('이벤트유형_옵션_조회_실패시_전체만_남고_목록은_정상_표시된다', async () => {
    // given
    setRole('WORKER');
    stubWorker({ eventTypesStatus: 500 });

    // when
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    // then: 옵션 실패가 목록 전체를 가리면 안 된다.
    await waitFor(() => {
      expect(screen.getByText('CCTV-W1')).toBeInTheDocument();
    });
    const select = screen.getByLabelText('이벤트') as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['']);
    expect(
      screen.queryByText('작업 목록을 불러올 수 없습니다'),
    ).not.toBeInTheDocument();
  });

  it('필터가_비어있으면_해당_파라미터를_전송하지_않는다', async () => {
    // given: BE 는 `q=` 같은 빈 값에 필터를 걸 수도, 400 을 낼 수도 있다 — 키째로 빼야 한다.
    setRole('WORKER');
    stubWorker();

    // when
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-W1')).toBeInTheDocument();
    });

    // then: 진입 기본값은 페이징만 명시 전송한다.
    const params = lastAssignmentParams(mock) ?? {};
    expect(params.page).toBe(0);
    expect(params.size).toBe(20);
    expect('q' in params).toBe(false);
    expect('workStatus' in params).toBe(false);
    expect('eventTypeCd' in params).toBe(false);
    expect('workerId' in params).toBe(false);
  });

  it('검색어_입력은_debounce_되어_연속_호출되지_않는다', async () => {
    // given: 요구는 "매 타이핑마다 서버 조회 금지" 다. 이 화면의 검색은 제출(조회) 기반이라
    // 타이핑 중에는 요청이 **0건**이며, 타이머 debounce 는 조회 클릭을 지연시킬 뿐이라 두지 않는다.
    setRole('WORKER');
    stubWorker();
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-W1')).toBeInTheDocument();
    });
    const before = assignmentCalls(mock).length;

    // when: 6글자 연속 입력
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('영상명 / 작업자명'), '강남대로교차로');

    // then: 글자 수만큼 요청이 나가지 않는다.
    expect(assignmentCalls(mock).length).toBe(before);

    // 제출하면 정확히 1회만 추가된다.
    await user.click(screen.getByRole('button', { name: /조회/ }));
    await waitFor(() => {
      expect(lastAssignmentParams(mock)?.q).toBe('강남대로교차로');
    });
    expect(assignmentCalls(mock).length).toBe(before + 1);
  });

  it('URL_필터로_진입하면_서버_요청에_반영된다', async () => {
    // given: 북마크·뒤로가기 보존 (URL 상태 유지)
    setRole('WORKER');
    stubWorker();

    // when
    renderWithProviders(<TaskListPage />, {
      initialEntries: ['/task?q=강남&status=IN_PROGRESS&eventTypeCd=EV02'],
    });

    // then
    await waitFor(() => {
      expect(lastAssignmentParams(mock)?.q).toBe('강남');
    });
    expect(lastAssignmentParams(mock)?.workStatus).toBe('IN_PROGRESS');
    expect(lastAssignmentParams(mock)?.eventTypeCd).toBe('EV02');
  });

  it('WORKER_화면에_없는_상태값은_URL_로_들어와도_무시된다', async () => {
    // given: `UNASSIGNED` 는 board 축 값이라 WORKER 상태 select 에 선택지가 없다.
    // 그대로 두면 select 는 빈칸인데 목록은 전체가 나오는 어긋난 화면이 된다.
    setRole('WORKER');
    stubWorker();

    // when
    renderWithProviders(<TaskListPage />, {
      initialEntries: ['/task?status=UNASSIGNED'],
    });
    await waitFor(() => {
      expect(screen.getByText('CCTV-W1')).toBeInTheDocument();
    });

    // then: 상태 select 는 '전체 상태' 이고 서버로도 나가지 않는다(BE allowlist 밖 = 400).
    expect(screen.getByLabelText('상태')).toHaveValue('');
    expect('workStatus' in (lastAssignmentParams(mock) ?? {})).toBe(false);
  });

  it('REVIEWER_화면은_기존_board_경로를_그대로_사용한다', async () => {
    // given: 회귀 가드 — 이번 변경은 WORKER 축만 건드린다.
    setRole('REVIEWER');
    mock.onGet('/tasks/board').reply(200, page([]));
    mock.onGet('/tasks/board/summary').reply(
      200,
      ok({
        total: 0,
        unassigned: 0,
        inProgress: 0,
        reviewPending: 0,
        completed: 0,
        rejected: 0,
      }),
    );
    mock
      .onGet('/tasks/board/event-types')
      .reply(200, ok({ items: ['EV01'], truncated: false }));
    mock.onGet('/users').reply(200, page([]));
    // WORKER 전용 경로가 호출되면 403 스팸이 된다.
    mock.onGet('/assignments').reply(403);
    mock.onGet('/assignments/event-types').reply(403);

    // when
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(
        mock.history.get.filter((r) => r.url === '/tasks/board').length,
      ).toBeGreaterThanOrEqual(1);
    });

    // then
    expect(assignmentCalls(mock)).toHaveLength(0);
    expect(optionCalls(mock)).toHaveLength(0);
  });
});
