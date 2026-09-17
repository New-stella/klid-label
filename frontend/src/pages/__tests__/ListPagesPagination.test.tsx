/**
 * 목록 화면 3종의 페이지네이션이 공용 컨트롤(UI-008)로 수렴했는지 지키는 회귀 가드.
 *
 * 배경 — 세 화면은 공용 컨트롤을 쓰지 않고 각자 페이저를 인라인으로 갖고 있었다.
 *  - 영상 처리 현황 / 작업 목록 : 번호를 **항상 앞쪽 7칸만** 그려, 페이지가 8개를 넘으면
 *    뒤쪽 페이지로 가는 번호가 아예 없었다(다음 버튼을 반복해 누르는 길만 남았다).
 *  - 증강 요청 : 번호 없이 이전/다음 뿐이라 마지막 페이지까지 그만큼 눌러야 했다.
 * 그래서 아래 ①번 단언(마지막 페이지로 가는 경로)이 이 파일의 핵심이다.
 *
 * 함께 지키는 것 — ②페이지 이동이 URL 직렬화 형식을 바꾸지 않을 것(북마크·뒤로가기)
 * ③0건·1페이지 경계에서 컨트롤이 렌더되지 않을 것.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useLocation } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { AugmentRequestPage } from '@/pages/AugmentRequestPage';
import { TaskListPage } from '@/pages/TaskListPage';
import { VideoListPage } from '@/pages/VideoListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return { ...actual, useNavigate: () => navigateMock };
});

// 마킹 팝업은 자체 API 훅을 렌더하므로 이 파일에서는 열지 않는 stub 으로 대체한다.
vi.mock('@/features/marking/components/MarkingModal', () => ({
  MarkingModal: () => null,
}));

function setRole(role: 'REVIEWER' | 'WORKER', sub = 'u-7') {
  useAuthStore.setState({
    token: 'dummy-jwt',
    claims: { sub, role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

function ok<T>(data: T) {
  return { success: true, data, message: null, errorCode: null };
}

/** 서버 페이지 응답 — `totalPages` 는 **서버가 주는 값**이며 화면이 되계산하지 않는다. */
function pageBody<T>(content: T[], totalPages: number, number = 0, size = 20) {
  return ok({
    content,
    totalElements: totalPages * size,
    totalPages,
    number,
    size,
  });
}

/** URL 단언용 프로브 — 페이지와 같은 MemoryRouter 안에서 현재 query string 을 노출한다. */
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-search">{location.search}</div>;
}

/** 현재 query string(퍼센트 인코딩 해제) — 한글 필터값을 그대로 단언하기 위해서다. */
function currentSearch() {
  return decodeURIComponent(screen.getByTestId('location-search').textContent ?? '');
}

/** 마지막 목록 요청의 쿼리 파라미터. */
function lastParams(mock: MockAdapter, url: string) {
  const calls = mock.history.get.filter((r) => r.url === url);
  return calls[calls.length - 1]?.params as Record<string, unknown> | undefined;
}

describe('영상 처리 현황 — 페이지네이션 공용 컨트롤 수렴', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    setRole('REVIEWER');
    mock.onGet('/users').reply(200, pageBody([], 0));
    mock.onGet('/users/workers').reply(200, pageBody([], 0));
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function stubVideos(totalPages: number) {
    mock.onGet('/videos').reply((config) => {
      const number = Number((config.params as { page?: number })?.page ?? 0);
      return [
        200,
        pageBody(
          [
            {
              id: 100 + number,
              cctvName: `CCTV-P${number}`,
              vmsClipId: '',
              eventName: '',
              eventTypeCd: '',
              localGov: '',
              frameCount: 0,
              status: 'COMPLETED',
              capturedAt: '2026-05-07T10:00:00Z',
            },
          ],
          totalPages,
          number,
        ),
      ];
    });
  }

  it('페이지가_많아도_마지막_페이지로_가는_경로가_있다', async () => {
    // given: 25페이지 — 구 인라인 페이저는 1~7 번호만 그려 25페이지 버튼이 없었다.
    stubVideos(25);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-P0')).toBeInTheDocument();
    });

    // when: 마지막 페이지 번호를 누른다.
    const last = screen.getByRole('button', { name: '25페이지' });
    await userEvent.setup().click(last);

    // then: 한 번의 조작으로 마지막 페이지가 조회된다.
    await waitFor(() => {
      expect(lastParams(mock, '/videos')?.page).toBe(24);
    });
  });

  it('페이지_이동이_기존_URL_형식_그대로_반영된다', async () => {
    // given: 필터가 걸린 상태로 진입한다(북마크 복원 상황).
    stubVideos(25);
    renderWithProviders(
      <>
        <VideoListPage />
        <LocationProbe />
      </>,
      { initialEntries: ['/video?cctvNameKeyword=강남&dataSttsCd=COMPLETED'] },
    );
    await waitFor(() => {
      expect(screen.getByText('CCTV-P0')).toBeInTheDocument();
    });

    // when: 2페이지로 이동한다.
    await userEvent.setup().click(screen.getByRole('button', { name: '2페이지' }));

    // then: 직렬화 형식(키 이름·순서·기본값 생략)이 종전 그대로다.
    await waitFor(() => {
      expect(currentSearch()).toBe(
        '?page=1&cctvNameKeyword=강남&dataSttsCd=COMPLETED',
      );
    });
    // 필터도 함께 왕복한다(페이지만 남고 필터가 떨어지면 북마크가 깨진다).
    expect(lastParams(mock, '/videos')).toMatchObject({
      page: 1,
      cctvNameKeyword: '강남',
      dataSttsCd: 'COMPLETED',
    });
  });

  it('한_페이지뿐이면_페이지네이션을_렌더하지_않는다', async () => {
    stubVideos(1);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-P0')).toBeInTheDocument();
    });

    expect(screen.queryByRole('navigation', { name: '페이지네이션' })).toBeNull();
  });

  it('0건이면_페이지네이션을_렌더하지_않는다', async () => {
    mock.onGet('/videos').reply(200, pageBody([], 0));
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });
    await waitFor(() => {
      expect(screen.getByText(/전체 0건/)).toBeInTheDocument();
    });

    expect(screen.queryByRole('navigation', { name: '페이지네이션' })).toBeNull();
  });
});

describe('작업 목록 — 페이지네이션 공용 컨트롤 수렴', () => {
  let mock: MockAdapter;

  const SUMMARY = {
    total: 500,
    unassigned: 100,
    inProgress: 100,
    reviewPending: 100,
    completed: 100,
    rejected: 100,
  };

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    setRole('REVIEWER');
    mock.onGet('/tasks/board/summary').reply(200, ok(SUMMARY));
    mock
      .onGet('/tasks/board/event-types')
      .reply(200, ok({ items: [], truncated: false }));
    mock.onGet('/users').reply(200, pageBody([], 0));
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function stubBoard(totalPages: number) {
    mock.onGet('/tasks/board').reply((config) => {
      const number = Number((config.params as { page?: number })?.page ?? 0);
      return [
        200,
        pageBody(
          [
            {
              videoId: 10 + number,
              cctvName: `CCTV-B${number}`,
              eventName: 'EV01',
              eventTypeCd: 'EV01',
              frameCount: 3,
              capturedAt: '2026-05-07T10:00:00Z',
              batchStatus: 'COMPLETED',
              status: 'UNASSIGNED',
              assignmentId: null,
              workerId: null,
              workerName: null,
              assignedAt: null,
              firstSrcSn: null,
            },
          ],
          totalPages,
          number,
        ),
      ];
    });
  }

  it('페이지가_많아도_마지막_페이지로_가는_경로가_있다', async () => {
    // given: 25페이지 — 구 인라인 페이저는 1~7 번호만 그렸다.
    stubBoard(25);
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-B0')).toBeInTheDocument();
    });

    // when: 마지막 페이지 번호를 누른다.
    await userEvent.setup().click(screen.getByRole('button', { name: '25페이지' }));

    // then: 배치 상태 축(status)·워크플로 축(workStatus)은 그대로 두고 페이지만 바뀐다.
    await waitFor(() => {
      expect(lastParams(mock, '/tasks/board')?.page).toBe(24);
    });
    expect(lastParams(mock, '/tasks/board')?.status).toBe('COMPLETED');
  });

  it('페이지_이동이_필터_URL_형식을_바꾸지_않는다', async () => {
    // given: 이 화면의 페이지 번호는 URL 에 싣지 않는다(필터·정렬만 싣는다).
    stubBoard(25);
    renderWithProviders(
      <>
        <TaskListPage />
        <LocationProbe />
      </>,
      // ★워크플로 축의 URL 키는 `status` 다(BE 파라미터명 `workStatus` 와 다르다).
      { initialEntries: ['/task?q=강남&status=UNASSIGNED'] },
    );
    await waitFor(() => {
      expect(screen.getByText('CCTV-B0')).toBeInTheDocument();
    });
    const before = currentSearch();

    // when: 마지막 페이지로 이동한다.
    await userEvent.setup().click(screen.getByRole('button', { name: '25페이지' }));
    await waitFor(() => {
      expect(lastParams(mock, '/tasks/board')?.page).toBe(24);
    });

    // then: URL 은 그대로다 — 페이지 번호가 새로 새어 들어가지도, 필터가 떨어지지도 않는다.
    expect(currentSearch()).toBe(before);
    expect(before).toContain('q=강남');
    expect(before).toContain('status=UNASSIGNED');
    expect(before).not.toContain('page=');
    // 워크플로 축은 BE 파라미터로도 살아 있어야 한다(축이 둘이라 한쪽만 남으면 목록이 갈린다).
    expect(lastParams(mock, '/tasks/board')?.workStatus).toBe('UNASSIGNED');
  });

  it('한_페이지뿐이면_페이지네이션을_렌더하지_않는다', async () => {
    stubBoard(1);
    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });
    await waitFor(() => {
      expect(screen.getByText('CCTV-B0')).toBeInTheDocument();
    });

    expect(screen.queryByRole('navigation', { name: '페이지네이션' })).toBeNull();
  });
});

describe('증강 요청 — 페이지네이션 공용 컨트롤 수렴', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    setRole('REVIEWER');
    mock.onGet('/augments').reply(200, pageBody([], 0, 0, 6));
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function stubVideos(totalPages: number) {
    mock.onGet('/videos').reply((config) => {
      const number = Number((config.params as { page?: number })?.page ?? 0);
      return [
        200,
        pageBody(
          [
            {
              id: 200 + number,
              cctvName: `CCTV-A${number}`,
              vmsClipId: '',
              eventName: '',
              eventTypeCd: '',
              localGov: '',
              frameCount: 0,
              status: 'COMPLETED',
              capturedAt: '2026-05-07T10:00:00Z',
            },
          ],
          totalPages,
          number,
        ),
      ];
    });
  }

  it('페이지가_많아도_마지막_페이지로_가는_경로가_있다', async () => {
    // given: 25페이지 — 구 페이저는 이전/다음뿐이라 24번 눌러야 도달했다.
    stubVideos(25);
    renderWithProviders(<AugmentRequestPage />);
    await waitFor(() => {
      expect(screen.getByText('CCTV-A0')).toBeInTheDocument();
    });

    // when: 마지막 페이지 번호를 누른다.
    await userEvent.setup().click(screen.getByRole('button', { name: '25페이지' }));

    // then: 검수 완료 영상만 조회하는 필터를 유지한 채 마지막 페이지가 조회된다.
    await waitFor(() => {
      expect(lastParams(mock, '/videos')?.page).toBe(24);
    });
    expect(lastParams(mock, '/videos')).toMatchObject({
      dataSttsCd: 'COMPLETED',
      reviewStatusCd: 'APPROVED',
    });
  });

  it('총건수는_페이저가_아니라_단계_머리글_배지가_말한다', async () => {
    // 사양(SCREEN-022)은 페이지 이동 컨트롤과 '검수 완료 N건' 배지를 **별도 요소**로 둔다.
    // 구 페이저는 같은 값을 '전체 N건 (x/y 페이지)' 로 한 번 더 말해 중복이었다.
    stubVideos(25);
    renderWithProviders(<AugmentRequestPage />);
    await waitFor(() => {
      expect(screen.getByText('CCTV-A0')).toBeInTheDocument();
    });

    expect(screen.getByText('검수 완료 500건')).toBeInTheDocument();
    expect(screen.queryByText(/전체 \d+건 \(\d+\/\d+/)).toBeNull();
  });

  it('한_페이지뿐이면_페이지네이션을_렌더하지_않는다', async () => {
    stubVideos(1);
    renderWithProviders(<AugmentRequestPage />);
    await waitFor(() => {
      expect(screen.getByText('CCTV-A0')).toBeInTheDocument();
    });

    expect(screen.queryByRole('navigation', { name: '페이지네이션' })).toBeNull();
  });
});
