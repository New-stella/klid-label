import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useLocation } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { SEARCH_DEBOUNCE_MS } from '@/features/review/components/ReviewListFilters';
import { useApproveReview } from '@/features/review/hooks/useReviewActions';
import { REVIEW_STATUS_LABEL } from '@/features/review/reviewListParams';
import { ReviewListPage } from '@/pages/ReviewListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';
import { useAuthStore } from '@/stores/useAuthStore';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual =
    await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

interface RowOverrides {
  id?: number;
  videoId?: number;
  cctvName?: string;
  workerName?: string;
  status?: string;
}

function reviewRow(o: RowOverrides = {}) {
  return {
    id: o.id ?? 10,
    videoId: o.videoId ?? 10,
    cctvName: o.cctvName ?? 'CCTV-A',
    workerId: 7,
    workerName: o.workerName ?? '홍길동',
    submittedAt: '2026-05-07T10:00:00Z',
    labelCount: 12,
    // ★ 응답은 FE 코드다(요청만 BE 코드) — 계약을 목이 뒤집으면 테스트가 결함을 못 잡는다.
    status: o.status ?? 'REVIEW_PENDING',
    eventName: '쓰러짐',
    eventTypeCd: 'EVT_FALL',
  };
}

function page<T>(content: T[], totalElements = content.length, totalPages = 1) {
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

/** BE 계약 정합 목 집계 — total === pending + inReview + approved + rejected. */
const SUMMARY = { total: 42, pending: 9, inReview: 11, approved: 19, rejected: 3 };

function listCalls(mock: MockAdapter) {
  return mock.history.get.filter((r) => r.url === '/reviews');
}

function lastListParams(mock: MockAdapter) {
  const calls = listCalls(mock);
  return calls[calls.length - 1]?.params as Record<string, unknown> | undefined;
}

function lastSummaryParams(mock: MockAdapter) {
  const calls = mock.history.get.filter((r) => r.url === '/reviews/summary');
  return calls[calls.length - 1]?.params as Record<string, unknown> | undefined;
}

/** URL 단언용 프로브 — 페이지와 같은 MemoryRouter 안에서 현재 query string 을 노출한다. */
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-search">{location.search}</div>;
}

function urlParams() {
  return new URLSearchParams(
    screen.getByTestId('location-search').textContent ?? '',
  );
}

/** 승인 mutation 을 목록과 **같은 QueryClient** 안에서 실행하기 위한 테스트 전용 트리거. */
function ApproveTrigger({ reviewId }: { reviewId: number }) {
  const { mutate } = useApproveReview();
  return (
    <button type="button" onClick={() => mutate({ reviewId })}>
      TEST_APPROVE
    </button>
  );
}

describe('ReviewListPage — 진입 기본값·서버 필터·정렬 (Phase 5)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    useAuthStore.setState({
      token: 'dummy-jwt',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function stub(options?: {
    rows?: ReturnType<typeof reviewRow>[];
    summaryStatus?: number;
    summary?: typeof SUMMARY;
    listStatus?: number;
    totalElements?: number;
    totalPages?: number;
  }) {
    const rows = options?.rows ?? [reviewRow()];
    if (options?.listStatus) {
      mock.onGet('/reviews').reply(options.listStatus);
    } else {
      mock
        .onGet('/reviews')
        .reply(
          200,
          page(rows, options?.totalElements ?? SUMMARY.total, options?.totalPages ?? 1),
        );
    }
    if (options?.summaryStatus) {
      mock.onGet('/reviews/summary').reply(options.summaryStatus);
    } else {
      mock.onGet('/reviews/summary').reply(200, ok(options?.summary ?? SUMMARY));
    }
  }

  function renderPage(entry = '/review') {
    return renderWithProviders(
      <>
        <ReviewListPage />
        <LocationProbe />
      </>,
      { initialEntries: [entry] },
    );
  }

  // ── H-3 / 진입 기본값 ────────────────────────────────────────────
  it('진입시_검수요청_상태와_제출일_오름차순으로_요청한다', async () => {
    stub();
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });

    const params = lastListParams(mock);
    // ★ BE 코드로 역매핑되어 나가야 한다 — FE 코드를 그대로 보내면 조용한 빈 결과다.
    expect(params?.status).toBe('PENDING');
    expect(params?.sort).toBe('submittedAt,asc');
    expect(params?.page).toBe(0);
    expect(params?.size).toBe(20);
    expect(params?.q).toBeUndefined();
  });

  it('진입시_기본값이_URL_에_기록된다', async () => {
    stub();
    renderPage();

    await waitFor(() => {
      expect(urlParams().get('status')).toBe('REVIEW_PENDING');
    });
    expect(urlParams().get('sort')).toBe('submittedAt,asc');
  });

  it('URL_의_상태값이_목록_요청과_select_에_모두_반영된다', async () => {
    stub({ rows: [reviewRow({ status: 'REJECTED' })] });
    renderPage('/review?status=REJECTED');

    await waitFor(() => {
      expect(lastListParams(mock)?.status).toBe('REJECTED');
    });
    expect(screen.getByLabelText('상태')).toHaveTextContent(REVIEW_STATUS_LABEL.REJECTED);
  });

  // ── H-6 / KPI 카드 ───────────────────────────────────────────────
  it('KPI_카드는_서버_집계_전체기준_숫자를_표시한다', async () => {
    stub();
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('kpi-pending')).toHaveTextContent('9');
    });
    expect(screen.getByTestId('kpi-inReview')).toHaveTextContent('11');
    expect(screen.getByTestId('kpi-approved')).toHaveTextContent('19');
    expect(screen.getByTestId('kpi-rejected')).toHaveTextContent('3');
    // 집계는 status 로 좁히지 않는다 — 좁히면 카드 하나만 값을 갖는다.
    expect(lastSummaryParams(mock)).not.toHaveProperty('status');
  });

  it('KPI_카드_클릭시_해당_상태_필터가_적용되고_페이지가_0으로_리셋된다', async () => {
    stub();
    renderPage('/review?status=ALL&sort=submittedAt,asc&page=3&size=20');

    await waitFor(() => {
      expect(screen.getByTestId('kpi-approved')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId('kpi-approved'));

    await waitFor(() => {
      expect(lastListParams(mock)?.status).toBe('APPROVED');
    });
    expect(lastListParams(mock)?.page).toBe(0);
    expect(urlParams().get('page')).toBe('0');
    expect(urlParams().get('status')).toBe('COMPLETED');
  });

  it('같은_카드를_다시_클릭하면_필터가_해제된다', async () => {
    stub();
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('kpi-rejected')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId('kpi-rejected'));
    await waitFor(() => {
      expect(lastListParams(mock)?.status).toBe('REJECTED');
    });

    await user.click(screen.getByTestId('kpi-rejected'));
    await waitFor(() => {
      expect(lastListParams(mock)?.status).toBeUndefined();
    });
    // 전체는 URL 에 ALL 로 남는다 — 키를 지우면 새로고침에 기본값(검수요청)으로 되돌아간다.
    expect(urlParams().get('status')).toBe('ALL');
  });

  it('선택된_카드에_aria_pressed_가_적용된다', async () => {
    stub();
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('kpi-pending')).toBeInTheDocument();
    });
    // 진입 기본값이 검수요청이므로 해당 카드가 선택 상태다.
    expect(screen.getByTestId('kpi-pending')).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByTestId('kpi-approved')).toHaveAttribute(
      'aria-pressed',
      'false',
    );

    // 키보드 접근 — 카드는 button 이라 Tab 포커스 + Enter/Space 로 토글된다.
    const user = userEvent.setup();
    screen.getByTestId('kpi-approved').focus();
    expect(screen.getByTestId('kpi-approved')).toHaveFocus();
    await user.keyboard('{Enter}');

    await waitFor(() => {
      expect(screen.getByTestId('kpi-approved')).toHaveAttribute(
        'aria-pressed',
        'true',
      );
    });
  });

  // ── H-4 / 페이지 리셋 ────────────────────────────────────────────
  it('검색어_변경시_페이지가_0으로_리셋된다', async () => {
    stub();
    renderPage('/review?status=ALL&sort=submittedAt,asc&page=5&size=20');

    await waitFor(() => {
      expect(screen.getByLabelText('영상명 / 작업자명')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.type(screen.getByLabelText('영상명 / 작업자명'), '강남');

    await waitFor(() => {
      expect(lastListParams(mock)?.q).toBe('강남');
    });
    expect(lastListParams(mock)?.page).toBe(0);
    expect(urlParams().get('page')).toBe('0');
  });

  it('상태_select_변경시_페이지가_0으로_리셋된다', async () => {
    stub();
    renderPage('/review?status=ALL&sort=submittedAt,asc&page=4&size=20');

    await waitFor(() => {
      expect(screen.getByLabelText('상태')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await selectRadixOption(user, screen.getByLabelText('상태'), REVIEW_STATUS_LABEL.REVIEWING);

    await waitFor(() => {
      expect(lastListParams(mock)?.status).toBe('IN_REVIEW');
    });
    expect(lastListParams(mock)?.page).toBe(0);
  });

  // ── H-2 / 정렬 ───────────────────────────────────────────────────
  it('sortable_컬럼은_제출일_축만이다', async () => {
    stub();
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });

    // DataTable 은 sortable 컬럼에만 aria-sort 를 부여한다.
    const sortableHeaders = screen
      .getAllByRole('columnheader')
      .filter((th) => th.getAttribute('aria-sort') !== null)
      .map((th) => th.textContent?.trim());
    expect(sortableHeaders).toEqual(['제출일']);

    // ★ 상태 축은 정렬 대상이 아니다 — 값이 한 종류로 수렴하는 기본 화면(검수요청)에서 누르면
    // 1차 정렬이 무효가 되고 BE tie-break(영상 ID 역순)가 실질 정렬이 되어 FIFO 가 깨진다.
    const statusTh = screen
      .getAllByRole('columnheader')
      .find((el) => el.textContent?.trim() === '상태');
    expect(statusTh?.getAttribute('aria-sort')).toBeNull();
    expect(
      within(statusTh as HTMLElement).queryByRole('button'),
    ).not.toBeInTheDocument();

    // BE allowlist 밖 컬럼도 정렬 버튼이 없어야 한다 — 있으면 조용한 무효 클릭이 된다.
    ['이벤트', '작업자', '라벨 수'].forEach((header) => {
      const th = screen
        .getAllByRole('columnheader')
        .find((el) => el.textContent?.trim() === header);
      expect(th?.getAttribute('aria-sort')).toBeNull();
    });
  });

  it('제출일_헤더_정렬_클릭시_방향이_토글되어_서버로_전달된다', async () => {
    stub();
    renderPage();

    await waitFor(() => {
      expect(lastListParams(mock)?.sort).toBe('submittedAt,asc');
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /제출일/ }));

    await waitFor(() => {
      expect(lastListParams(mock)?.sort).toBe('submittedAt,desc');
    });
    expect(urlParams().get('sort')).toBe('submittedAt,desc');
    expect(lastListParams(mock)?.page).toBe(0);
  });

  it('URL_의_미등록_정렬키는_기본_정렬로_정규화되어_전송된다', async () => {
    stub();
    // BE 는 미등록 키를 400 이 아니라 조용히 폴백한다 — FE 가 그대로 보내면
    // "URL 은 바뀌었는데 순서는 그대로" 가 된다.
    renderPage('/review?sort=eventName,desc');

    await waitFor(() => {
      expect(lastListParams(mock)?.sort).toBe('submittedAt,asc');
    });
  });

  // ── H-5 / summary 실패 격리 ──────────────────────────────────────
  it('KPI_조회_실패가_목록_표시를_막지_않는다', async () => {
    stub({ summaryStatus: 500 });
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });
    expect(screen.getByTestId('kpi-error')).toBeInTheDocument();
    // 페이지 레벨 에러는 목록 쿼리만 결정한다.
    expect(
      screen.queryByText('검수 목록을 불러올 수 없습니다'),
    ).not.toBeInTheDocument();
  });

  // ── 클라이언트 필터 제거 ─────────────────────────────────────────
  it('클라이언트에서_행을_다시_거르지_않는다', async () => {
    // 서버가 status=PENDING 으로 걸러 보낸 결과에 다른 상태가 섞여 있어도 화면은 그대로 그린다.
    // (여기서 다시 거르면 목록·총건수·KPI 가 서로 다른 값을 말한다.)
    stub({
      rows: [
        reviewRow({ id: 1, videoId: 1, cctvName: 'CCTV-1', status: 'REVIEW_PENDING' }),
        reviewRow({ id: 2, videoId: 2, cctvName: 'CCTV-2', status: 'COMPLETED' }),
        reviewRow({ id: 3, videoId: 3, cctvName: 'CCTV-3', workerName: '김작업' }),
      ],
    });
    renderPage('/review?q=존재하지않는검색어&status=ALL');

    await waitFor(() => {
      expect(screen.getByText('CCTV-1')).toBeInTheDocument();
    });
    expect(screen.getByText('CCTV-2')).toBeInTheDocument();
    expect(screen.getByText('CCTV-3')).toBeInTheDocument();
  });

  // ── 초기화 ───────────────────────────────────────────────────────
  it('초기화하면_진입_기본값으로_되돌아간다', async () => {
    stub();
    renderPage('/review?status=REJECTED&sort=submittedAt,desc&q=강남&page=2&size=20');

    await waitFor(() => {
      expect(lastListParams(mock)?.status).toBe('REJECTED');
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '초기화' }));

    await waitFor(() => {
      // ★ 전체가 아니라 **검수요청·오래된순** 으로 되돌아간다.
      expect(lastListParams(mock)?.status).toBe('PENDING');
    });
    expect(lastListParams(mock)?.sort).toBe('submittedAt,asc');
    expect(lastListParams(mock)?.q).toBeUndefined();
    expect(lastListParams(mock)?.page).toBe(0);
    expect(urlParams().get('status')).toBe('REVIEW_PENDING');
  });

  it('빈_문자열_필터값은_요청_파라미터에서_생략된다', async () => {
    stub();
    renderPage('/review?status=ALL&q=');

    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });
    const params = lastListParams(mock);
    expect(params).not.toHaveProperty('q');
    expect(params).not.toHaveProperty('status');
  });

  it('필터링된_상태임을_화면에_표시한다', async () => {
    stub({ rows: [] });
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('review-active-filter')).toHaveTextContent(
        '검수요청',
      );
    });
  });

  // ── H-7 / 승인 후 갱신 ───────────────────────────────────────────
  it('승인_처리_후_목록과_KPI_가_갱신된다', async () => {
    mock.onGet('/reviews').reply(200, page([reviewRow()], SUMMARY.total));
    let summaryHit = 0;
    mock.onGet('/reviews/summary').reply(() => {
      summaryHit += 1;
      // 승인 후 재조회에서는 검수요청이 1건 줄어든다.
      return [
        200,
        ok(summaryHit === 1 ? SUMMARY : { ...SUMMARY, pending: SUMMARY.pending - 1 }),
      ];
    });
    mock.onPost('/reviews/10/approve').reply(200, ok(reviewRow({ status: 'COMPLETED' })));

    renderWithProviders(
      <>
        <ReviewListPage />
        <ApproveTrigger reviewId={10} />
        <LocationProbe />
      </>,
      { initialEntries: ['/review'] },
    );

    await waitFor(() => {
      expect(screen.getByTestId('kpi-pending')).toHaveTextContent('9');
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'TEST_APPROVE' }));

    await waitFor(() => {
      expect(screen.getByTestId('kpi-pending')).toHaveTextContent('8');
    });
  });

  it('행_액션_버튼은_영상명과_함께_읽힌다', async () => {
    stub();
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });
    const row = screen.getByText('CCTV-A').closest('tr') as HTMLElement;
    expect(
      within(row).getByRole('button', { name: /검수시작 CCTV-A/ }),
    ).toBeInTheDocument();
  });

  // ── [수정 1] 초기화 ↔ debounce 경합 ───────────────────────────────
  it('초기화_직후_대기중이던_검색어가_되살아나지_않는다', async () => {
    stub();
    // URL 에 q 가 없는 상태(values.q === '')에서 입력 → 초기화해도 values.q 가 '' → '' 라
    // 동기화 effect·debounce cleanup 이 모두 돌지 않던 경로.
    renderPage('/review?status=REJECTED&sort=submittedAt,asc');

    await waitFor(() => {
      expect(lastListParams(mock)?.status).toBe('REJECTED');
    });

    const input = screen.getByLabelText('영상명 / 작업자명');
    // debounce 창(300ms) 안에서 확실히 끝나도록 동기 이벤트로 입력·초기화를 연속 실행한다.
    fireEvent.change(input, { target: { value: '강남' } });
    fireEvent.click(screen.getByRole('button', { name: '초기화' }));

    // 입력값은 즉시 진입 기본값(빈 검색어)으로 동기화된다.
    expect(input).toHaveValue('');

    // 대기 중이던 타이머가 살아 있었다면 이 시점에 q=강남 이 다시 붙는다.
    await new Promise((resolve) => setTimeout(resolve, SEARCH_DEBOUNCE_MS + 100));

    expect(urlParams().get('q')).toBeNull();
    expect(input).toHaveValue('');
    expect(lastListParams(mock)?.q).toBeUndefined();
    expect(lastListParams(mock)?.status).toBe('PENDING');
  });

  // ── [수정 3] page 범위 초과 복귀 ─────────────────────────────────
  it('총_페이지가_줄면_현재_페이지가_범위_안으로_복귀한다', async () => {
    // 검수요청 9건(1페이지)인데 URL 은 5페이지 — 표는 0건인데 페이지네이션은 "1-9 / 총 9건" 인
    // 자기모순 화면이 되던 경로.
    stub({ rows: [], totalElements: 9, totalPages: 1 });
    renderPage('/review?status=REVIEW_PENDING&sort=submittedAt,asc&page=5&size=20');

    await waitFor(() => {
      expect(urlParams().get('page')).toBe('0');
    });
    await waitFor(() => {
      expect(lastListParams(mock)?.page).toBe(0);
    });
  });

  // ── [수정 4] 목록 실패 ↔ 0건 구분 ────────────────────────────────
  it('목록_조회_실패시_빈_목록_문구가_표시되지_않는다', async () => {
    stub({ listStatus: 500 });
    renderPage();

    await waitFor(() => {
      expect(
        screen.getByText('검수 목록을 불러올 수 없습니다'),
      ).toBeInTheDocument();
    });
    // "불러오지 못함" 과 "0건" 이 화면에서 구분돼야 한다.
    expect(
      screen.queryByText('검수요청 항목이 없습니다'),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(/총 0건/)).not.toBeInTheDocument();
  });

  // ── [수정 5] 갱신 중 표시 ────────────────────────────────────────
  it('필터_전환_중에는_갱신중_표시가_뜬다', async () => {
    let hit = 0;
    mock.onGet('/reviews').reply(() => {
      hit += 1;
      if (hit === 1) return [200, page([reviewRow()], SUMMARY.total)];
      // 두 번째(필터 전환) 요청은 왕복 시간을 갖는다 — 그 사이 표는 이전 결과다.
      return new Promise((resolve) => {
        setTimeout(() => resolve([200, page([reviewRow()], SUMMARY.total)]), 150);
      });
    });
    mock.onGet('/reviews/summary').reply(200, ok(SUMMARY));
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('review-refreshing')).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByTestId('kpi-approved'));

    await waitFor(() => {
      expect(screen.getByTestId('review-refreshing')).toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.queryByTestId('review-refreshing')).not.toBeInTheDocument();
    });
  });

  // ── [수정 7] URL 정규화 ─────────────────────────────────────────
  it('비정상_status_와_sort_가_동시에_있어도_URL_이_정규화된다', async () => {
    stub();
    renderPage('/review?status=BOGUS&sort=labelPayload,asc');

    await waitFor(() => {
      expect(lastListParams(mock)?.status).toBe('PENDING');
    });
    // 요청·표시만 정규화하고 URL 을 두면 어긋난 주소가 그대로 공유·북마크된다.
    await waitFor(() => {
      expect(urlParams().get('status')).toBe('REVIEW_PENDING');
    });
    expect(urlParams().get('sort')).toBe('submittedAt,asc');
  });

  // ── [수정 8] KPI 라벨 단일 정의 ──────────────────────────────────
  it('KPI_카드_라벨은_공용_상태_라벨_상수를_따른다', async () => {
    stub();
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('kpi-pending')).toBeInTheDocument();
    });
    expect(screen.getByTestId('kpi-pending')).toHaveTextContent(
      REVIEW_STATUS_LABEL.REVIEW_PENDING,
    );
    expect(screen.getByTestId('kpi-inReview')).toHaveTextContent(
      REVIEW_STATUS_LABEL.REVIEWING,
    );
    expect(screen.getByTestId('kpi-approved')).toHaveTextContent(
      REVIEW_STATUS_LABEL.COMPLETED,
    );
    expect(screen.getByTestId('kpi-rejected')).toHaveTextContent(
      REVIEW_STATUS_LABEL.REJECTED,
    );
  });

  // ── [수정 9] Label in Name (WCAG 2.5.3) ──────────────────────────
  it('행_액션의_접근성_이름이_표시_라벨과_일치한다', async () => {
    stub({
      rows: [
        reviewRow({ id: 1, videoId: 1, cctvName: 'CCTV-1', status: 'REVIEW_PENDING' }),
        reviewRow({ id: 2, videoId: 2, cctvName: 'CCTV-2', status: 'REVIEWING' }),
        reviewRow({ id: 3, videoId: 3, cctvName: 'CCTV-3', status: 'COMPLETED' }),
        reviewRow({ id: 4, videoId: 4, cctvName: 'CCTV-4', status: 'REJECTED' }),
      ],
    });
    renderPage('/review?status=ALL&sort=submittedAt,asc');

    await waitFor(() => {
      expect(screen.getByText('CCTV-1')).toBeInTheDocument();
    });

    const nameOf = (cctv: string) =>
      within(screen.getByText(cctv).closest('tr') as HTMLElement).getByRole(
        'button',
      );

    expect(nameOf('CCTV-1')).toHaveAccessibleName(`검수시작 CCTV-1`);
    expect(nameOf('CCTV-2')).toHaveAccessibleName(`이어서 검수 CCTV-2`);
    // 승인·반려 행은 "결과보기" 로 보이는데 "검수 시작" 으로 읽히던 경로.
    expect(nameOf('CCTV-3')).toHaveAccessibleName(`결과보기 CCTV-3`);
    expect(nameOf('CCTV-4')).toHaveAccessibleName(`결과보기 CCTV-4`);
  });
});
