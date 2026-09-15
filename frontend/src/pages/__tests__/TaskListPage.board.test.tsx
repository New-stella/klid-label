import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useLocation } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { TaskListPage } from '@/pages/TaskListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';
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

interface BoardRowOverrides {
  videoId?: number;
  cctvName?: string;
  eventTypeCd?: string | null;
  status?: string;
}

function boardRow(o: BoardRowOverrides = {}) {
  return {
    videoId: o.videoId ?? 10,
    cctvName: o.cctvName ?? 'CCTV-BOARD',
    eventName: o.eventTypeCd ?? 'EV01',
    eventTypeCd: o.eventTypeCd ?? 'EV01',
    frameCount: 3,
    capturedAt: '2026-05-07T10:00:00Z',
    batchStatus: 'COMPLETED',
    status: o.status ?? 'UNASSIGNED',
    assignmentId: null,
    workerId: null,
    workerName: null,
    assignedAt: null,
    firstSrcSn: null,
  };
}

function page<T>(content: T[], totalPages = 1, totalElements = content.length) {
  return {
    success: true,
    data: {
      content,
      totalElements,
      totalPages,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  };
}

function ok<T>(data: T) {
  return { success: true, data, message: null, errorCode: null };
}

/**
 * BE 계약 정합 목 집계.
 * - `total === unassigned + inProgress + reviewPending + completed + rejected`
 * - workStatus 필터가 없을 때 `total` 은 목록 `totalElements` 와 같아야 한다
 *   (목이 이 불변식을 깨면 "카드와 목록이 다른 집합을 센다"는 결함을 테스트가 못 잡는다).
 */
const SUMMARY = {
  total: 42,
  unassigned: 13,
  inProgress: 11,
  reviewPending: 9,
  completed: 6,
  rejected: 3,
};

/** 목록 요청 목록(호출 횟수 단언용). */
function boardCalls(mock: MockAdapter) {
  return mock.history.get.filter((r) => r.url === '/tasks/board');
}

/** 마지막 목록 요청의 쿼리 파라미터. */
function lastBoardParams(mock: MockAdapter) {
  const calls = boardCalls(mock);
  return calls[calls.length - 1]?.params as Record<string, unknown> | undefined;
}

/** URL 단언용 프로브 — 페이지와 같은 MemoryRouter 안에서 현재 query string 을 노출한다. */
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-search">{location.search}</div>;
}

describe('TaskListPage — 서버 필터·정렬·KPI (Phase 3)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    // WORKER 시각 진입 시 함께 호출되는 옵션 엔드포인트 기본 스텁 — 스텁이 없으면 mock adapter 가
    // passthrough 로 실제 네트워크를 시도해 테스트가 플레이키해진다.
    mock
      .onGet('/assignments/event-types')
      .reply(200, ok({ items: [], truncated: false }));
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function stubReviewer(options?: {
    rows?: ReturnType<typeof boardRow>[];
    totalPages?: number;
    summaryStatus?: number;
    eventTypesStatus?: number;
    eventTypes?: { items: string[]; truncated: boolean };
  }) {
    const rows = options?.rows ?? [boardRow()];
    // totalElements 는 summary.total 과 같아야 계약이 성립한다(workStatus 미적용 상태).
    mock
      .onGet('/tasks/board')
      .reply(200, page(rows, options?.totalPages ?? 1, SUMMARY.total));
    if (options?.summaryStatus) {
      mock.onGet('/tasks/board/summary').reply(options.summaryStatus);
    } else {
      mock.onGet('/tasks/board/summary').reply(200, ok(SUMMARY));
    }
    if (options?.eventTypesStatus) {
      mock.onGet('/tasks/board/event-types').reply(options.eventTypesStatus);
    } else {
      mock
        .onGet('/tasks/board/event-types')
        .reply(
          200,
          ok(options?.eventTypes ?? { items: ['EV01', 'EV02'], truncated: false }),
        );
    }
    mock.onGet('/users').reply(200, page([]));
  }

  it('진입시_배치상태_COMPLETED_와_등록일_최신순_파라미터로_요청한다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const params = lastBoardParams(mock);
    expect(params?.status).toBe('COMPLETED');
    expect(params?.sort).toEqual(['regDt,desc']);
    expect(params?.page).toBe(0);
    expect(params?.size).toBe(20);
    // 빈 값 전송 금지 (BE 400)
    expect(params?.q).toBeUndefined();
    expect(params?.workStatus).toBeUndefined();
    expect(params?.eventTypeCd).toBeUndefined();
  });

  it('KPI_카드는_서버_집계_전체기준_숫자를_표시한다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByTestId('kpi-total')).toHaveTextContent('42');
    });
    // 현재 페이지 1건이지만 카드는 전체 기준 숫자를 보여준다.
    expect(screen.getByTestId('kpi-unassigned')).toHaveTextContent('13');
    expect(screen.getByTestId('kpi-inProgress')).toHaveTextContent('11');
    expect(screen.getByTestId('kpi-reviewPending')).toHaveTextContent('9');
    expect(screen.getByTestId('kpi-rejected')).toHaveTextContent('3');
  });

  it('미배정_카드_클릭시_workStatus_UNASSIGNED_로_요청한다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByTestId('kpi-unassigned')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId('kpi-unassigned'));

    await waitFor(() => {
      expect(lastBoardParams(mock)?.workStatus).toBe('UNASSIGNED');
    });
  });

  it('미배정_카드_클릭이_status_UNASSIGNED_로_나가지_않는다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByTestId('kpi-unassigned')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId('kpi-unassigned'));

    await waitFor(() => {
      expect(lastBoardParams(mock)?.workStatus).toBe('UNASSIGNED');
    });
    // 배치 축은 그대로 COMPLETED — 바뀌면 카드 숫자와 목록 총건수가 어긋난다.
    expect(lastBoardParams(mock)?.status).toBe('COMPLETED');
  });

  it('작업중_카드_클릭시_workStatus_PENDING_으로_요청한다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByTestId('kpi-inProgress')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId('kpi-inProgress'));

    await waitFor(() => {
      // BE mapBoardStatus 는 IN_PROGRESS 를 반환하지 않는다 — 작업중 = PENDING.
      expect(lastBoardParams(mock)?.workStatus).toBe('PENDING');
    });
  });

  it('같은_카드를_다시_클릭하면_필터가_해제된다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByTestId('kpi-rejected')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId('kpi-rejected'));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.workStatus).toBe('REJECTED');
    });

    await user.click(screen.getByTestId('kpi-rejected'));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.workStatus).toBeUndefined();
    });
  });

  it('전체_카드_클릭시_workStatus_필터가_비워진다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByTestId('kpi-rejected')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId('kpi-rejected'));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.workStatus).toBe('REJECTED');
    });

    await user.click(screen.getByTestId('kpi-total'));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.workStatus).toBeUndefined();
    });
  });

  it('카드_클릭시_페이지가_0으로_리셋된다', async () => {
    setRole('REVIEWER');
    stubReviewer({ totalPages: 3 });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '다음 페이지' }));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.page).toBe(1);
    });

    await user.click(screen.getByTestId('kpi-reviewPending'));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.workStatus).toBe('REVIEW_PENDING');
    });
    expect(lastBoardParams(mock)?.page).toBe(0);
  });

  it('선택된_카드에_aria_pressed_가_적용된다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByTestId('kpi-unassigned')).toBeInTheDocument();
    });

    const card = screen.getByTestId('kpi-unassigned');
    expect(card.tagName).toBe('BUTTON');
    expect(card).toHaveAttribute('aria-pressed', 'false');

    const user = userEvent.setup();
    await user.click(card);

    await waitFor(() => {
      expect(screen.getByTestId('kpi-unassigned')).toHaveAttribute(
        'aria-pressed',
        'true',
      );
    });
    // 다른 카드는 선택 해제 상태를 유지한다.
    expect(screen.getByTestId('kpi-rejected')).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('KPI_카드는_키보드로도_필터를_적용한다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByTestId('kpi-rejected')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    screen.getByTestId('kpi-rejected').focus();
    await user.keyboard('{Enter}');

    await waitFor(() => {
      expect(lastBoardParams(mock)?.workStatus).toBe('REJECTED');
    });
  });

  it('이벤트유형_옵션은_items_배열에서_읽는다', async () => {
    setRole('REVIEWER');
    stubReviewer({ eventTypes: { items: ['EV01', 'EV02'], truncated: false } });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    const select = screen.getByLabelText('이벤트');
    await user.click(select);
    const optionLabels = (await screen.findAllByRole('option')).map((o) => o.textContent);
    expect(optionLabels).toEqual(['전체', 'EV01', 'EV02']);
    // status 만 전달한다 (검색어로 옵션이 좁아지면 되돌아갈 수 없다).
    const call = mock.history.get.find(
      (r) => r.url === '/tasks/board/event-types',
    );
    expect(call?.params).toEqual({ status: 'COMPLETED' });
  });

  it('이벤트유형_옵션이_잘리면_안내를_노출한다', async () => {
    setRole('REVIEWER');
    stubReviewer({ eventTypes: { items: ['EV01'], truncated: true } });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByTestId('event-type-truncated')).toBeInTheDocument();
    });
  });

  it('이벤트유형_옵션_조회_실패시_전체만_남고_목록은_정상_표시된다', async () => {
    setRole('REVIEWER');
    stubReviewer({ eventTypesStatus: 500 });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });
    const user = userEvent.setup();
    const select = screen.getByLabelText('이벤트');
    await user.click(select);
    const optionLabels = (await screen.findAllByRole('option')).map((o) => o.textContent);
    expect(optionLabels).toEqual(['전체']);
    expect(
      screen.queryByText('작업 목록을 불러올 수 없습니다'),
    ).not.toBeInTheDocument();
  });

  it('KPI_조회_실패가_목록_표시를_막지_않는다', async () => {
    setRole('REVIEWER');
    stubReviewer({ summaryStatus: 500 });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });
    // 페이지 전체 에러 배너는 뜨지 않고 카드 영역에만 표시된다.
    expect(
      screen.queryByText('작업 목록을 불러올 수 없습니다'),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId('kpi-error')).toBeInTheDocument();
  });

  it('필터_변경시_클라이언트에서_행을_다시_거르지_않는다', async () => {
    setRole('REVIEWER');
    stubReviewer({ rows: [boardRow({ cctvName: 'CCTV-SERVER-ROW' })] });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-SERVER-ROW')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.type(screen.getByLabelText('영상명 / 작업자명'), '전혀다른검색어');
    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => {
      expect(lastBoardParams(mock)?.q).toBe('전혀다른검색어');
    });
    // 서버가 준 행은 클라이언트에서 다시 걸러지지 않는다 (서버가 진실원).
    expect(screen.getByText('CCTV-SERVER-ROW')).toBeInTheDocument();
  });

  it('페이지_전환시_선택된_체크박스가_초기화된다', async () => {
    setRole('REVIEWER');
    stubReviewer({ totalPages: 3 });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('CCTV-BOARD 선택'));
    expect(screen.getByTestId('bulk-assign-bar')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '다음 페이지' }));

    await waitFor(() => {
      expect(screen.queryByTestId('bulk-assign-bar')).not.toBeInTheDocument();
    });
  });

  it('필터_변경시_선택된_체크박스가_초기화된다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('CCTV-BOARD 선택'));
    expect(screen.getByTestId('bulk-assign-bar')).toBeInTheDocument();

    await user.click(screen.getByTestId('kpi-unassigned'));

    await waitFor(() => {
      expect(screen.queryByTestId('bulk-assign-bar')).not.toBeInTheDocument();
    });
  });

  it('상태_필터_옵션은_BE_workStatus_축과_1대1이다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    const select = screen.getByLabelText('상태');
    await user.click(select);
    const optionLabels = (await screen.findAllByRole('option')).map((o) => o.textContent);
    expect(optionLabels).toEqual([
      '전체 상태',
      '미배정',
      '배정 완료(작업중)',
      '검수요청',
      '완료',
      '반려',
    ]);
    // BE 가 절대 반환하지 않는 값 — 고르면 항상 0건이고 전송하면 400. 옵션 라벨에도 없어야 한다.
    expect(optionLabels).not.toContain('작업중');
  });

  it('URL_필터로_진입하면_서버_요청에_반영된다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, {
      initialEntries: ['/task?status=REJECTED&q=강남&eventTypeCd=EV01'],
    });

    await waitFor(() => {
      expect(lastBoardParams(mock)?.workStatus).toBe('REJECTED');
    });
    expect(lastBoardParams(mock)?.q).toBe('강남');
    expect(lastBoardParams(mock)?.eventTypeCd).toBe('EV01');
  });

  it.each(['UNASSIGNED', 'PENDING', 'COMPLETED', 'REJECTED'])(
    '구_URL_status_%s_는_워크플로_축으로_해석되고_배치축은_COMPLETED_고정이다',
    async (value) => {
      // 변경 전 화면의 `status` 는 워크플로 축이었다. 배치 축으로 해석되면
      // 파이프라인 미완료 영상까지 목록에 올라오고 부제("처리 완료된 영상만 표시")가 거짓이 된다.
      setRole('REVIEWER');
      stubReviewer();

      renderWithProviders(<TaskListPage />, {
        initialEntries: [`/task?status=${value}`],
      });

      await waitFor(() => {
        expect(lastBoardParams(mock)?.workStatus).toBe(value);
      });
      expect(lastBoardParams(mock)?.status).toBe('COMPLETED');
    },
  );

  it('WORKER_시각에서는_board_요청이_없고_KPI_카드는_본인_배정분_집계로_표시된다', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(
      200,
      page([
        {
          id: 100,
          videoId: 1,
          cctvName: 'CCTV-WORKER',
          workerId: 7,
          workerName: '홍길동',
          status: 'PENDING',
          assignedAt: '2026-05-07T10:00:00Z',
        },
      ]),
    );
    // REVIEWER 전용 엔드포인트 — 호출되면 403 스팸.
    mock.onGet('/tasks/board').reply(403);
    mock.onGet('/tasks/board/summary').reply(403);
    mock.onGet('/tasks/board/event-types').reply(403);

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-WORKER')).toBeInTheDocument();
    });

    const reviewerOnly = mock.history.get.filter((r) =>
      r.url?.startsWith('/tasks/board'),
    );
    expect(reviewerOnly).toHaveLength(0);
    // KPI 카드는 역할과 무관하게 보인다 — WORKER 는 본인 배정 목록 기반 클라이언트 집계 4카드.
    expect(screen.getByTestId('kpi-total')).toHaveTextContent('1');
    expect(screen.getByTestId('kpi-inProgress')).toBeInTheDocument();
    expect(screen.getByTestId('kpi-reviewPending')).toBeInTheDocument();
    expect(screen.getByTestId('kpi-rejected')).toBeInTheDocument();
    // 미배정 카드는 REVIEWER 서버 집계 전용이다.
    expect(screen.queryByTestId('kpi-unassigned')).not.toBeInTheDocument();
  });

  it('WORKER_전용_IN_PROGRESS_필터가_URL_진입에서_유지된다', async () => {
    setRole('WORKER');
    // Phase 4 — 이 축은 이제 **서버 필터**다(BE allowlist 에 IN_PROGRESS 가 있다).
    // 서버는 이미 걸러진 결과를 돌려주고 화면은 그대로 그린다.
    mock.onGet('/assignments').reply(
      200,
      page([
        {
          id: 100,
          videoId: 1,
          cctvName: 'CCTV-WORKING',
          workerId: 7,
          workerName: '홍길동',
          status: 'IN_PROGRESS',
          assignedAt: '2026-05-07T10:00:00Z',
        },
      ]),
    );

    renderWithProviders(<TaskListPage />, {
      initialEntries: ['/task?status=IN_PROGRESS'],
    });

    await waitFor(() => {
      expect(screen.getByText('CCTV-WORKING')).toBeInTheDocument();
    });
    // URL 왕복에서 값이 버려지면 새로고침 후 필터가 사라진다.
    expect(screen.getByLabelText('상태')).toHaveTextContent('작업중');
    // 그리고 그 값이 서버로 나가야 한다 — 화면에서만 거르면 뒷페이지 항목이 영원히 안 보인다.
    const calls = mock.history.get.filter((r) => r.url === '/assignments');
    expect(
      (calls[calls.length - 1]?.params as Record<string, unknown>)?.workStatus,
    ).toBe('IN_PROGRESS');
  });

  it('검색어_입력은_100자로_제한된다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const input = screen.getByLabelText('영상명 / 작업자명') as HTMLInputElement;
    expect(input.maxLength).toBe(100);
  });

  it('KPI_카드_영역은_로딩중_스켈레톤을_표시한다', async () => {
    setRole('REVIEWER');
    mock.onGet('/tasks/board').reply(200, page([boardRow()]));
    mock.onGet('/tasks/board/event-types').reply(200, ok({ items: [], truncated: false }));
    mock.onGet('/users').reply(200, page([]));
    // summary 는 응답 지연 — 로딩 상태 유지
    mock.onGet('/tasks/board/summary').reply(() => new Promise(() => {}));

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByTestId('kpi-loading')).toBeInTheDocument();
    });
    // 로딩을 0건으로 오인하지 않도록 숫자를 렌더하지 않는다.
    expect(screen.queryByTestId('kpi-total')).not.toBeInTheDocument();

    const rows = within(screen.getByRole('table'));
    expect(await rows.findByText('CCTV-BOARD')).toBeInTheDocument();
  });

  // ─── 컬럼 헤더 정렬 (R6/AC-6) ───────────────────────────────────────────────

  it('헤더_정렬_클릭시_sort_파라미터가_서버로_전달된다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '촬영일시' }));

    await waitFor(() => {
      // 클릭한 컬럼이 **1순위**여야 한다 — 뒤에 붙으면 regDt 가 정렬을 지배해 무효 클릭이 된다.
      expect(lastBoardParams(mock)?.sort).toEqual(['shtDt,desc', 'regDt,desc']);
    });
    const header = screen
      .getByRole('button', { name: '촬영일시' })
      .closest('th');
    expect(header).toHaveAttribute('aria-sort', 'descending');
  });

  it('헤더_재클릭시_정렬_방향이_토글된다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '촬영일시' }));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.sort).toEqual(['shtDt,desc', 'regDt,desc']);
    });

    await user.click(screen.getByRole('button', { name: '촬영일시' }));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.sort).toEqual(['shtDt,asc', 'regDt,desc']);
    });
    expect(
      screen.getByRole('button', { name: '촬영일시' }).closest('th'),
    ).toHaveAttribute('aria-sort', 'ascending');
  });

  it('컬럼키_videoId_는_서버_정렬키_rawSn_으로_매핑된다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '영상 ID' }));

    await waitFor(() => {
      expect(
        (lastBoardParams(mock)?.sort as string[] | undefined)?.[0],
      ).toBe('rawSn,desc');
    });
  });

  it('URL_sort_파라미터가_왕복에서_보존된다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, {
      initialEntries: ['/task?sort=rawSn,asc'],
    });

    await waitFor(() => {
      expect(lastBoardParams(mock)?.sort).toEqual(['rawSn,asc']);
    });
    // URL 이 통째로 교체되며 sort 가 조용히 지워지면 안 된다(헤더 표시도 유지).
    expect(
      screen.getByRole('button', { name: '영상 ID' }).closest('th'),
    ).toHaveAttribute('aria-sort', 'ascending');
  });

  it('정렬_키가_3개를_넘지_않는다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '촬영일시' }));
    await user.click(screen.getByRole('button', { name: '영상 ID' }));
    await user.click(screen.getByRole('button', { name: '촬영일시' }));

    await waitFor(() => {
      const sort = lastBoardParams(mock)?.sort as string[];
      // BE 는 4개부터 400 이다.
      expect(sort.length).toBeLessThanOrEqual(3);
      expect(new Set(sort.map((s) => s.split(',')[0])).size).toBe(sort.length);
    });
  });

  // ─── 요청 횟수·입력 보존·에러/페이지 복구 (MEDIUM) ─────────────────────────

  it('카드_클릭시_직전_페이지_번호로_목록_요청이_한번_더_나가지_않는다', async () => {
    setRole('REVIEWER');
    stubReviewer({ totalPages: 3 });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '다음 페이지' }));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.page).toBe(1);
    });

    await user.click(screen.getByTestId('kpi-reviewPending'));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.workStatus).toBe('REVIEW_PENDING');
    });

    // 페이지 리셋이 effect 로 미뤄지면 {page:1} 과 {page:0} 두 건이 나간다.
    const filtered = boardCalls(mock).filter(
      (c) => (c.params as Record<string, unknown>).workStatus === 'REVIEW_PENDING',
    );
    expect(filtered).toHaveLength(1);
    expect((filtered[0].params as Record<string, unknown>).page).toBe(0);
  });

  it('미제출_검색어는_KPI_카드_클릭으로_사라지지_않는다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    const input = screen.getByLabelText('영상명 / 작업자명') as HTMLInputElement;
    await user.type(input, '강남');

    await user.click(screen.getByTestId('kpi-unassigned'));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.workStatus).toBe('UNASSIGNED');
    });

    // 입력 중이던 검색어가 유지되어야 한다(조회는 아직 누르지 않았으므로 서버로는 안 나간다).
    expect(input).toHaveValue('강남');
    expect(lastBoardParams(mock)?.q).toBeUndefined();
  });

  it('목록_조회_실패시_배정_액션이_잠긴다', async () => {
    setRole('REVIEWER');
    mock
      .onGet('/tasks/board')
      .replyOnce(200, page([boardRow()], 1, SUMMARY.total));
    mock.onGet('/tasks/board').reply(500);
    mock.onGet('/tasks/board/summary').reply(200, ok(SUMMARY));
    mock
      .onGet('/tasks/board/event-types')
      .reply(200, ok({ items: [], truncated: false }));
    mock.onGet('/users').reply(200, page([]));

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /새로고침/ }));

    await waitFor(() => {
      expect(
        screen.getByText('작업 목록을 불러올 수 없습니다'),
      ).toBeInTheDocument();
    });
    // 이전 결과(stale)가 남아 있어도 그 위에서 배정하면 안 된다.
    expect(screen.getByLabelText('CCTV-BOARD 선택')).toBeDisabled();
    expect(screen.getByRole('button', { name: '배정' })).toBeDisabled();
  });

  it('두번째_초기화도_페이지를_되돌린다', async () => {
    setRole('REVIEWER');
    stubReviewer({ totalPages: 3 });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /초기화/ }));
    await user.click(screen.getByRole('button', { name: '다음 페이지' }));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.page).toBe(1);
    });

    // 모듈 상수를 그대로 넘기면 참조가 같아 두 번째 초기화가 no-op 이 된다.
    await user.click(screen.getByRole('button', { name: /초기화/ }));

    await waitFor(() => {
      // 공용 페이지네이션의 번호 버튼은 접근명이 'N페이지' 다(번호만 읽히던 구 인라인 페이저와 다름).
      expect(screen.getByRole('button', { name: '1페이지' })).toHaveAttribute(
        'aria-current',
        'page',
      );
    });
    expect(lastBoardParams(mock)?.page).toBe(0);
  });

  it('총_페이지_수가_줄면_현재_페이지가_범위_안으로_되돌아온다', async () => {
    setRole('REVIEWER');
    let totalPages = 3;
    mock
      .onGet('/tasks/board')
      .reply(() => [200, page([boardRow()], totalPages, SUMMARY.total)]);
    mock.onGet('/tasks/board/summary').reply(200, ok(SUMMARY));
    mock
      .onGet('/tasks/board/event-types')
      .reply(200, ok({ items: [], truncated: false }));
    mock.onGet('/users').reply(200, page([]));

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '다음 페이지' }));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.page).toBe(1);
    });

    totalPages = 1;
    await user.click(screen.getByRole('button', { name: /새로고침/ }));

    // 범위를 벗어난 페이지로 계속 요청하면 목록이 빈 채로 페이지네이션 UI 까지 사라져 복구가 불가능하다.
    await waitFor(() => {
      expect(lastBoardParams(mock)?.page).toBe(0);
    });
  });

  // ─── 전체 건수 원천 · 초기화 범위 (DEV_FIX 2차) ──────────────────────────

  it('REVIEWER_시각의_전체_건수는_서버_totalElements_를_따른다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    // 서버 필터가 적용된 전체 기준 숫자 — 현재 페이지 1건이어도 42건이다.
    expect(screen.getByText(/전체 42건/)).toBeInTheDocument();
  });

  it('WORKER_시각의_전체_건수는_서버_필터_결과와_일치한다', async () => {
    setRole('WORKER');
    // Phase 4 — 필터가 서버로 가므로 "전체 N건" 의 원천도 서버 totalElements 다.
    // 현재 페이지 3행 / 전체 42건: 화면 행 수로 덮어쓰면 뒷페이지가 없는 것처럼 보인다.
    const rows = [
      {
        id: 100,
        videoId: 1,
        cctvName: 'CCTV-PENDING',
        workerId: 7,
        workerName: '홍길동',
        status: 'PENDING',
        assignedAt: '2026-05-07T10:00:00Z',
      },
      {
        id: 101,
        videoId: 2,
        cctvName: 'CCTV-REVIEW',
        workerId: 7,
        workerName: '홍길동',
        status: 'REVIEW_PENDING',
        assignedAt: '2026-05-07T10:00:00Z',
      },
      {
        id: 102,
        videoId: 3,
        cctvName: 'CCTV-DONE',
        workerId: 7,
        workerName: '홍길동',
        status: 'COMPLETED',
        assignedAt: '2026-05-07T10:00:00Z',
      },
    ];
    mock.onGet('/assignments').replyOnce(200, page(rows, 3, 42));
    // 필터 적용 후 — 서버가 1건으로 좁혀 응답한다.
    mock.onGet('/assignments').reply(200, page([rows[1]], 1, 1));

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-REVIEW')).toBeInTheDocument();
    });
    expect(screen.getByText(/전체 42건/)).toBeInTheDocument();

    const user = userEvent.setup();
    await selectRadixOption(user, screen.getByLabelText('상태'), '검수요청');
    await user.click(screen.getByRole('button', { name: /조회/ }));

    await waitFor(() => {
      expect(screen.queryByText('CCTV-DONE')).not.toBeInTheDocument();
    });
    expect(screen.getByText(/전체 1건/)).toBeInTheDocument();
    expect(screen.queryByText(/전체 42건/)).not.toBeInTheDocument();
  });

  it('초기화하면_정렬도_기본값으로_되돌아간다', async () => {
    setRole('REVIEWER');
    stubReviewer();

    renderWithProviders(
      <>
        <TaskListPage />
        <LocationProbe />
      </>,
      { initialEntries: ['/task'] },
    );
    await waitFor(() => {
      expect(screen.getByText('CCTV-BOARD')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '촬영일시' }));
    await waitFor(() => {
      expect(lastBoardParams(mock)?.sort).toEqual(['shtDt,desc', 'regDt,desc']);
    });
    expect(screen.getByTestId('location-search').textContent).toContain('sort=');

    await user.click(screen.getByRole('button', { name: /초기화/ }));

    // ① 요청 파라미터 ② URL ③ 헤더 aria-sort — 셋 다 기본값으로 돌아와야 한다.
    await waitFor(() => {
      expect(lastBoardParams(mock)?.sort).toEqual(['regDt,desc']);
    });
    expect(screen.getByTestId('location-search').textContent).not.toContain(
      'sort=',
    );
    expect(
      screen.getByRole('button', { name: '촬영일시' }).closest('th'),
    ).toHaveAttribute('aria-sort', 'none');
  });

  it('목록_실패_배너는_WORKER_에게_배정_기능을_안내하지_않는다', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(500);

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(
        screen.getByText('작업 목록을 불러올 수 없습니다'),
      ).toBeInTheDocument();
    });
    // WORKER 에게는 배정 기능 자체가 없다.
    expect(screen.queryByText(/배정 기능은 새로고침/)).not.toBeInTheDocument();
  });

  it('갱신_후_화면에_없는_선택은_해제된다', async () => {
    setRole('REVIEWER');
    const rows = [
      boardRow({ videoId: 10, cctvName: 'CCTV-A' }),
      boardRow({ videoId: 11, cctvName: 'CCTV-B' }),
    ];
    mock.onGet('/tasks/board').replyOnce(200, page(rows, 1, 2));
    mock.onGet('/tasks/board').reply(200, page([rows[0]], 1, 1));
    mock.onGet('/tasks/board/summary').reply(200, ok(SUMMARY));
    mock
      .onGet('/tasks/board/event-types')
      .reply(200, ok({ items: [], truncated: false }));
    mock.onGet('/users').reply(200, page([]));

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-B')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('CCTV-B 선택'));
    expect(screen.getByTestId('bulk-assign-bar')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /새로고침/ }));

    // 화면에 없는 영상이 일괄 배정에 섞이면 안 된다.
    await waitFor(() => {
      expect(screen.queryByText('CCTV-B')).not.toBeInTheDocument();
    });
    expect(screen.queryByTestId('bulk-assign-bar')).not.toBeInTheDocument();
  });
});
