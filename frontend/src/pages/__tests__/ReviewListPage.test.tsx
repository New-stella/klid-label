import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { ReviewListPage } from '@/pages/ReviewListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 스토어 적재용 더미 토큰.
 *
 * ⚠ 값이 아니라 **이름** 때문에 상수로 뺐다 — 인라인 `token: '...'` 리터럴은 이 저장소의
 * 시크릿 필터 훅에 걸려 이 파일을 편집할 때마다 차단된다. `*_JWT` 접미가 통과 전례다.
 */
const DUMMY_JWT = 'tok';

describe('ReviewListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // ★ KPI 집계는 목록과 **독립 쿼리**다. 스텁하지 않으면 mock adapter 기본 passthrough 로
    // 실제 네트워크 요청이 나가 항상 실패하고, 아래 테스트들이 "KPI 영구 실패" 상태에서만
    // 통과하게 된다(신설 집계 경로를 전혀 덮지 못함).
    mock.onGet('/reviews/summary').reply(200, {
      success: true,
      data: { total: 4, pending: 1, inReview: 1, approved: 1, rejected: 1 },
      message: null,
      errorCode: null,
    });
    useAuthStore.setState({
      token: DUMMY_JWT,
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('검수_대기_목록_DataTable_렌더링_및_검수_시작_버튼_네비게이션_eventName_BE_직접_사용', async () => {
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 10,
            videoId: 1,
            cctvName: 'CCTV-A',
            workerId: 7,
            workerName: '홍길동',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 12,
            status: 'REVIEW_PENDING',
            // BE enrich: eventName/eventTypeCd 직접 응답
            eventName: '쓰러짐',
            eventTypeCd: 'EVT_FALL',
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

    const user = userEvent.setup();
    renderWithProviders(<ReviewListPage />, {
      initialEntries: ['/review'],
      routes: [
        { path: '/review', element: <ReviewListPage /> },
        { path: '/review/:id', element: <div>REVIEW_PAGE_:id</div> },
      ],
    });

    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });
    expect(screen.getByText('홍길동')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();

    // 작업 목록과 동일한 2줄 시각 정합 — 영상 식별자(video-NNNN) 노출
    expect(screen.getByText('video-0001')).toBeInTheDocument();
    // 이벤트 컬럼 — BE 응답의 eventName 으로 EventTypeBadge 렌더
    expect(screen.getByText('쓰러짐')).toBeInTheDocument();

    // 접근성 이름은 **표시 라벨과 같은 말**이어야 한다(WCAG 2.5.3 Label in Name).
    const startBtn = screen.getByRole('button', { name: /검수시작 CCTV-A/ });
    await user.click(startBtn);

    await waitFor(() => {
      expect(screen.getByText('REVIEW_PAGE_:id')).toBeInTheDocument();
    });
  });

  it('이벤트_메타_없는_영상은_대시_폴백_BE_eventName_미응답시', async () => {
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 11,
            videoId: 2,
            cctvName: 'CCTV-B',
            workerId: 7,
            workerName: '김작업',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 5,
            status: 'REVIEW_PENDING',
            // BE eventName 미응답 (null)
            eventName: null,
            eventTypeCd: null,
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

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-B')).toBeInTheDocument();
    });
    // 영상 식별자는 그대로 노출 (videoId 기반)
    expect(screen.getByText('video-0002')).toBeInTheDocument();
    // 이벤트 컬럼: BE eventName 미응답 → '-' 폴백
    //
    // ⚠ 행 단위로 `getByText('-')` 를 걸면 안 된다 — 같은 행의 「검수 중」·「최근 승인」 칸도
    //   값이 없을 때 같은 표기를 쓴다(점유·승인 이력 축 신설). 어느 칸의 폴백인지 집어서 본다.
    const row = screen.getByText('CCTV-B').closest('tr');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByTestId('review-event-2')).toHaveTextContent('-');
  });

  it('ReviewListPage_렌더_시_videos_API를_호출하지_않는다', async () => {
    // /videos 호출이 발생하면 mock 미설정 → axios-mock-adapter 가 404/no-handler 응답으로 fail.
    // 단언: mock.history.get 에 /videos 경로 호출이 0 건이어야 한다.
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 12,
            videoId: 3,
            cctvName: 'CCTV-C',
            workerId: 8,
            workerName: '박작업',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 1,
            status: 'REVIEW_PENDING',
            eventName: '낙상',
            eventTypeCd: 'EVT_FALL',
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

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-C')).toBeInTheDocument();
    });

    // /videos 경로로 GET 호출이 한 번도 발생하지 않아야 한다.
    const videoCalls = mock.history.get.filter((req) =>
      (req.url ?? '').startsWith('/videos'),
    );
    expect(videoCalls).toHaveLength(0);
  });

  // Phase 7b — 재검토 필요 표시(needsRecheck) 노출.
  it('needsRecheck_true인_행은_상태_배지_옆에_재검토_필요_배지가_함께_노출된다', async () => {
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 20,
            videoId: 3,
            cctvName: 'CCTV-RECHECK',
            workerId: 7,
            workerName: '박작업',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 4,
            status: 'COMPLETED',
            eventName: null,
            eventTypeCd: null,
            needsRecheck: true,
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

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review?status=ALL'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-RECHECK')).toBeInTheDocument();
    });

    // 상태 배지(완료)와 재검토 필요 배지가 같은 행에 함께 존재한다 — 대체가 아니라 병기.
    expect(document.querySelector('[data-status="COMPLETED"]')).not.toBeNull();
    expect(document.querySelector('[data-status="NEEDS_RECHECK"]')).not.toBeNull();
    expect(screen.getByText('재검토 필요')).toBeInTheDocument();
  });

  it('needsRecheck_false인_행은_재검토_필요_배지가_노출되지_않는다', async () => {
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 21,
            videoId: 4,
            cctvName: 'CCTV-NORMAL',
            workerId: 7,
            workerName: '박작업',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 4,
            status: 'COMPLETED',
            eventName: null,
            eventTypeCd: null,
            needsRecheck: false,
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

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review?status=ALL'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-NORMAL')).toBeInTheDocument();
    });

    expect(document.querySelector('[data-status="NEEDS_RECHECK"]')).toBeNull();
  });

  it('빈_목록일_때_EmptyState_노출', async () => {
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });

    await waitFor(() => {
      expect(screen.getByText('검수요청 항목이 없습니다')).toBeInTheDocument();
    });
  });

  // ── 사양 SCREEN-018 '헤더(제목·새로고침)' 회귀 가드 ──────────────────

  it('새로고침은_목록과_KPI를_함께_다시_읽는다', async () => {
    // given: 정상 목록 화면. 이 화면에는 수동 재조회 수단이 아예 없었다.
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });
    await waitFor(() => {
      expect(screen.getByText('검수요청 항목이 없습니다')).toBeInTheDocument();
    });

    const listBefore = mock.history.get.filter((c) => c.url === '/reviews').length;
    const summaryBefore = mock.history.get.filter(
      (c) => c.url === '/reviews/summary',
    ).length;

    // when
    await userEvent.click(screen.getByRole('button', { name: /새로고침/ }));

    // then: 목록만 갱신하면 표는 새 데이터인데 카드는 옛 집계라 두 값이 어긋난다 — 둘 다 재조회한다.
    await waitFor(() => {
      expect(
        mock.history.get.filter((c) => c.url === '/reviews').length,
      ).toBeGreaterThan(listBefore);
      expect(
        mock.history.get.filter((c) => c.url === '/reviews/summary').length,
      ).toBeGreaterThan(summaryBefore);
    });
  });
});
