import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { VideoListPage } from '@/pages/VideoListPage';
import {
  createTestQueryClient,
  renderWithProviders,
} from '@/test/renderWithProviders';
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

// MarkingModal 은 자체 API 훅(useCreateMarking)/AssignModal 을 렌더하므로
// open prop 전달만 검증하는 stub 으로 대체한다(AssignModal stub 정합).
// onMarked/onClose 콜백을 트리거하는 버튼을 노출해 상위 handleMarked 연동을 검증한다.
vi.mock('@/features/marking/components/MarkingModal', () => ({
  MarkingModal: ({
    open,
    rawSn,
    onMarked,
    onClose,
  }: {
    open: boolean;
    rawSn: number;
    onMarked?: () => void;
    onClose?: () => void;
  }) =>
    open ? (
      <div data-testid="marking-modal">
        MarkingModal rawSn={rawSn}
        <button type="button" onClick={() => onMarked?.()}>
          마킹완료-stub
        </button>
        <button type="button" onClick={() => onClose?.()}>
          닫기-stub
        </button>
      </div>
    ) : null,
}));

function setRole(role: 'REVIEWER' | 'WORKER', sub = 'u-7') {
  // VideoListPage 는 claims 만 읽는다(token 미참조) → 토큰 문자열 하드코딩 회피.
  useAuthStore.setState({
    claims: { sub, role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

interface VideoRow {
  id: number;
  cctvName: string;
  status: string;
  deIdntfYn?: string;
  deidentStatus?: string;
  workerId?: number | null;
  workerName?: string | null;
  assignmentId?: number | null;
  assignStatus?: string | null;
}

function mockVideos(mock: MockAdapter, rows: VideoRow[]) {
  const content = rows.map((r) => ({
    vmsClipId: '',
    eventName: '',
    eventTypeCd: '',
    localGov: '',
    frameCount: 0,
    capturedAt: '2026-05-07T10:00:00Z',
    ...r,
  }));
  mock.onGet('/videos').reply(200, {
    success: true,
    data: {
      content,
      totalElements: content.length,
      totalPages: 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  });
}

describe('VideoListPage 마킹 진입', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    // 배정 모달(작업자 목록) 호출 대비 — 기본 빈 목록.
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 100 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/users/workers').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 100 },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('MARKING_READY_비식별완료_미배정_영상에_마킹_버튼_노출', async () => {
    setRole('REVIEWER');
    mockVideos(mock, [
      {
        id: 1,
        cctvName: 'CCTV-READY',
        status: 'MARKING_READY',
        deIdntfYn: 'Y',
        deidentStatus: 'DONE',
        workerId: null,
      },
    ]);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-READY')).toBeInTheDocument();
    });

    // 라벨은 사양 SCREEN-008 의 '마킹 설정'이다(마킹 즉시 실행이 아니라 방식 선택 팝업을 연다).
    // 구 단언은 `/CCTV-READY 마킹/` 접두 일치라 문구가 무엇이든 통과했다 — 정확 일치로 좁힌다.
    expect(
      screen.getByRole('button', { name: 'CCTV-READY 마킹 설정' }),
    ).toBeInTheDocument();
    // 미배정이므로 재배정 버튼은 없다.
    expect(screen.queryByRole('button', { name: /재배정/ })).not.toBeInTheDocument();
  });

  it('PROCESSING_COMPLETED_FAILED_PENDING_영상엔_마킹_버튼_미노출', async () => {
    setRole('REVIEWER');
    mockVideos(mock, [
      { id: 11, cctvName: 'CCTV-PROC', status: 'PROCESSING', deIdntfYn: 'Y', workerId: null },
      { id: 12, cctvName: 'CCTV-DONE', status: 'COMPLETED', deIdntfYn: 'Y', workerId: null },
      { id: 13, cctvName: 'CCTV-FAIL', status: 'FAILED', deIdntfYn: 'Y', workerId: null },
      { id: 14, cctvName: 'CCTV-PEND', status: 'PENDING', deIdntfYn: 'Y', workerId: null },
    ]);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-PROC')).toBeInTheDocument();
    });

    expect(screen.queryByRole('button', { name: /마킹/ })).not.toBeInTheDocument();
  });

  it('비식별_미완_IN_PROGRESS_MARKING_READY_영상엔_마킹_버튼_미노출', async () => {
    setRole('REVIEWER');
    mockVideos(mock, [
      {
        id: 21,
        cctvName: 'CCTV-DEIDENT-IP',
        status: 'MARKING_READY',
        deIdntfYn: 'N',
        deidentStatus: 'IN_PROGRESS',
        workerId: null,
      },
    ]);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-DEIDENT-IP')).toBeInTheDocument();
    });

    expect(screen.queryByRole('button', { name: /마킹/ })).not.toBeInTheDocument();
  });

  it('비식별_실패_FAILED_MARKING_READY_영상엔_마킹_버튼_미노출', async () => {
    setRole('REVIEWER');
    mockVideos(mock, [
      {
        id: 22,
        cctvName: 'CCTV-DEIDENT-FAIL',
        status: 'MARKING_READY',
        deIdntfYn: 'F',
        deidentStatus: 'FAILED',
        workerId: null,
      },
    ]);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-DEIDENT-FAIL')).toBeInTheDocument();
    });

    expect(screen.queryByRole('button', { name: /마킹/ })).not.toBeInTheDocument();
  });

  it('이미_배정된_영상엔_재배정_버튼_그대로_노출_무회귀', async () => {
    setRole('REVIEWER');
    mockVideos(mock, [
      {
        id: 31,
        cctvName: 'CCTV-ASSIGNED',
        status: 'MARKING_READY',
        deIdntfYn: 'Y',
        workerId: 7,
        workerName: '홍길동',
        assignmentId: 500,
        assignStatus: 'IN_PROGRESS',
      },
    ]);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-ASSIGNED')).toBeInTheDocument();
    });

    expect(
      screen.getByRole('button', { name: /CCTV-ASSIGNED 작업자 재배정/ }),
    ).toBeInTheDocument();
    // 배정된 영상에는 마킹 버튼이 노출되지 않는다.
    expect(screen.queryByRole('button', { name: /마킹/ })).not.toBeInTheDocument();
  });

  it('WORKER면_마킹_버튼_미노출', async () => {
    setRole('WORKER');
    mockVideos(mock, [
      {
        id: 41,
        cctvName: 'CCTV-WORKER-VIEW',
        status: 'MARKING_READY',
        deIdntfYn: 'Y',
        workerId: null,
      },
    ]);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-WORKER-VIEW')).toBeInTheDocument();
    });

    expect(screen.queryByRole('button', { name: /마킹/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /재배정/ })).not.toBeInTheDocument();
  });

  it('마킹_버튼_클릭_시_MarkingModal_오픈', async () => {
    setRole('REVIEWER');
    mockVideos(mock, [
      {
        id: 51,
        cctvName: 'CCTV-OPEN',
        status: 'MARKING_READY',
        deIdntfYn: 'Y',
        workerId: null,
      },
    ]);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-OPEN')).toBeInTheDocument();
    });

    // 클릭 전에는 모달 미노출
    expect(screen.queryByTestId('marking-modal')).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /CCTV-OPEN 마킹/ }));

    await waitFor(() => {
      expect(screen.getByTestId('marking-modal')).toBeInTheDocument();
    });
    expect(screen.getByTestId('marking-modal')).toHaveTextContent('rawSn=51');
  });

  it('마킹성공_콜백시_videos_assignments_무효화되고_모달_닫힘', async () => {
    setRole('REVIEWER');
    mockVideos(mock, [
      {
        id: 61,
        cctvName: 'CCTV-MARKED',
        status: 'MARKING_READY',
        deIdntfYn: 'Y',
        deidentStatus: 'DONE',
        workerId: null,
      },
    ]);

    const queryClient = createTestQueryClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video'],
      queryClient,
    });

    await waitFor(() => {
      expect(screen.getByText('CCTV-MARKED')).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /CCTV-MARKED 마킹/ }));
    await waitFor(() => {
      expect(screen.getByTestId('marking-modal')).toBeInTheDocument();
    });

    // stub 의 onMarked 트리거 → 상위 handleMarked 실행
    await user.click(screen.getByRole('button', { name: '마킹완료-stub' }));

    // (a) videos·assignments 쿼리 무효화 (VIDEO_KEYS.all / ASSIGNMENT_KEYS.all)
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['videos'] });
    });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['assignments'] });

    // (b) markingTarget=null → 모달 닫힘
    await waitFor(() => {
      expect(screen.queryByTestId('marking-modal')).not.toBeInTheDocument();
    });
  });

  it('검수완료_배정영상은_재배정과_마킹_버튼_모두_미노출_상세만', async () => {
    // workerId != null && assignStatus === 'COMPLETED' → 작업 종결. 재배정/마킹 불가.
    setRole('REVIEWER');
    mockVideos(mock, [
      {
        id: 71,
        cctvName: 'CCTV-COMPLETED',
        status: 'MARKING_READY',
        deIdntfYn: 'Y',
        deidentStatus: 'DONE',
        workerId: 7,
        workerName: '홍길동',
        assignmentId: 700,
        assignStatus: 'COMPLETED',
      },
    ]);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-COMPLETED')).toBeInTheDocument();
    });

    expect(screen.queryByRole('button', { name: /재배정/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /마킹/ })).not.toBeInTheDocument();
    // 상세 버튼은 여전히 노출
    expect(screen.getByRole('button', { name: /상세/ })).toBeInTheDocument();
  });

  // ── 사양 SCREEN-008 '에러=ErrorState(재시도 버튼)' 회귀 가드 ──────────

  it('조회_실패시_재시도가_실제로_재조회한다', async () => {
    // given: 첫 조회가 실패한다. `refetch` 는 지역 변수로만 있고 ErrorState 에 onRetry 가
    // 연결돼 있지 않아, 실패 화면에서 빠져나올 수단이 없던 것이 이 가드의 대상이다.
    setRole('REVIEWER');
    mock.onGet('/videos').replyOnce(500);
    mockVideos(mock, [
      { id: 91, cctvName: 'CCTV-RETRY', status: 'MARKING_READY' },
    ]);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    const retry = await screen.findByRole('button', { name: '다시 시도' });
    const callsBefore = mock.history.get.filter((c) => c.url === '/videos').length;

    // when: 재시도를 누른다
    await userEvent.click(retry);

    // then: 목록 요청이 다시 나가고 데이터가 채워진다(버튼이 장식이 아님을 확인)
    await waitFor(() => {
      expect(
        mock.history.get.filter((c) => c.url === '/videos').length,
      ).toBeGreaterThan(callsBefore);
    });
    expect(await screen.findByText('CCTV-RETRY')).toBeInTheDocument();
  });
});
