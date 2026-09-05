import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// jsdom 환경에서 konva 가 native canvas 모듈을 요구하므로 mock 으로 대체.
vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ReviewPage, composeRejectReason } from '@/pages/ReviewPage';
import { useReviewSelectionStore } from '@/features/review/store/useReviewSelectionStore';

const baseReview = {
  id: 10,
  videoId: 1,
  cctvName: 'CCTV-1',
  workerId: 7,
  workerName: '홍길동',
  submittedAt: '2026-05-07T10:00:00Z',
  labelCount: 12,
};

describe('ReviewPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // store 초기화 (다른 테스트의 pendingIssues 등이 잔존하지 않도록).
    useReviewSelectionStore.getState().clear();
    useReviewSelectionStore.getState().setCurrentFrameIdx(0);
    // 이슈는 비어있음
    mock.onGet('/reviews/10/issues').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
    // Phase 3 — 프레임 조회 (LabelCanvas 의존성)
    mock.onGet('/reviews/1/frames').reply(200, {
      success: true,
      data: { videoId: 1, totalFrames: 0, frames: [] },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
  });

  it('검수_시작_버튼_클릭시_상태_REVIEWING_전이', async () => {
    // 첫 GET — REVIEW_PENDING
    let getCount = 0;
    mock.onGet('/reviews/10').reply(() => {
      getCount += 1;
      return [
        200,
        {
          success: true,
          data: {
            ...baseReview,
            status: getCount === 1 ? 'REVIEW_PENDING' : 'REVIEWING',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    let started = false;
    mock.onPost('/reviews/10/start').reply(() => {
      started = true;
      return [
        200,
        {
          success: true,
          data: { ...baseReview, status: 'REVIEWING' },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    // 진입 시 자동 startReview 호출 (REVIEW_PENDING → REVIEWING)
    await waitFor(() => {
      expect(started).toBe(true);
    });
  });

  it('승인시_상태_COMPLETED_전이', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });

    let approveBody: unknown;
    mock.onPost('/reviews/10/approve').reply((config) => {
      approveBody = config.data ? JSON.parse(config.data) : null;
      return [
        200,
        {
          success: true,
          data: { ...baseReview, status: 'COMPLETED' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [
        { path: '/review/:id', element: <ReviewPage /> },
        { path: '/review', element: <div>REVIEW_LIST</div> },
      ],
    });

    // 승인 버튼 클릭 → ConfirmDialog 노출
    const approveButtons = await screen.findAllByRole('button', { name: '승인' });
    await user.click(approveButtons[0]);

    // 확정 버튼 클릭
    const confirmButton = await screen.findByRole('button', { name: '승인 확정' });
    await user.click(confirmButton);

    await waitFor(() => {
      expect(approveBody).toBeDefined();
    });
    // navigate('/review') 확인
    await waitFor(() => {
      expect(screen.getByText('REVIEW_LIST')).toBeInTheDocument();
    });
  });

  it('H6_동시승인충돌_409는_라벨없음_다이얼로그를_띄우지_않는다', async () => {
    // DEV_FIX H6 — 승인 경로의 409 에는 '라벨 0건'(REVIEW_NO_LABEL) 외에 <b>동시 승인 충돌·상태 전이
    //   불가</b>(CONFLICT)도 있다. 상태코드만 보고 분기하면 라벨이 있는 영상의 낙관적 잠금 충돌에도
    //   "라벨이 없는 영상입니다" 다이얼로그가 뜨고, 확인 시 noLabelConfirmed=true 재요청 → BE 400 →
    //   "승인 실패" 로 끝나는 오도 경로가 된다. errorCode 로 구분해야 한다.
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });
    const approveBodies: unknown[] = [];
    mock.onPost('/reviews/10/approve').reply((config) => {
      approveBodies.push(config.data ? JSON.parse(config.data) : null);
      return [
        409,
        {
          success: false,
          data: null,
          message: '다른 검수자가 먼저 처리했습니다.',
          errorCode: 'CONFLICT',
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [
        { path: '/review/:id', element: <ReviewPage /> },
        { path: '/review', element: <div>REVIEW_LIST</div> },
      ],
    });

    const approveButtons = await screen.findAllByRole('button', { name: '승인' });
    await user.click(approveButtons[0]);
    const confirmButton = await screen.findByRole('button', { name: '승인 확정' });
    await user.click(confirmButton);

    await waitFor(() => expect(approveBodies.length).toBe(1));
    // '라벨 없음' 확인 다이얼로그가 뜨면 안 된다 → 재요청(noLabelConfirmed)도 발생하지 않는다.
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '라벨 없음 확인 후 승인' })).not.toBeInTheDocument(),
    );
    expect(approveBodies.length).toBe(1);
  });

  it('H6_라벨0건_409는_errorCode_REVIEW_NO_LABEL_로_구분해_확인_다이얼로그를_띄운다', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });
    mock.onPost('/reviews/10/approve').reply(409, {
      success: false,
      data: null,
      message: "라벨이 없는 영상입니다. 객체가 없는 영상이 맞다면 '라벨 없음' 확인 후 승인하세요.",
      errorCode: 'REVIEW_NO_LABEL',
    });

    const user = userEvent.setup();
    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [
        { path: '/review/:id', element: <ReviewPage /> },
        { path: '/review', element: <div>REVIEW_LIST</div> },
      ],
    });

    const approveButtons = await screen.findAllByRole('button', { name: '승인' });
    await user.click(approveButtons[0]);
    const confirmButton = await screen.findByRole('button', { name: '승인 확정' });
    await user.click(confirmButton);

    // 라벨 0건 사유일 때만 확인 다이얼로그가 열린다.
    expect(await screen.findByRole('button', { name: '라벨 없음 확인 후 승인' })).toBeInTheDocument();
  });

  it('ReviewPage_반려시_reason_에_검수의견과_pending_이슈_합쳐_전송', async () => {
    // store 사전 설정 — 컴포넌트 마운트 전 미리 주입.
    useReviewSelectionStore.getState().clear();
    useReviewSelectionStore.getState().setReviewComment('전반적으로 미흡합니다');
    useReviewSelectionStore
      .getState()
      .addPendingIssue('박스 어긋남', 11);
    useReviewSelectionStore
      .getState()
      .addPendingIssue('객체 누락', null);

    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });

    let rejectBody: { reason: string } | null = null;
    mock.onPost('/reviews/10/reject').reply((config) => {
      rejectBody = config.data ? JSON.parse(config.data) : null;
      return [
        200,
        {
          success: true,
          data: { ...baseReview, status: 'REJECTED' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [
        { path: '/review/:id', element: <ReviewPage /> },
        { path: '/review', element: <div>REVIEW_LIST</div> },
      ],
    });

    // 반려 버튼 클릭 → RejectModal 노출.
    const rejectButtons = await screen.findAllByRole('button', { name: '반려' });
    await user.click(rejectButtons[0]);

    // 사유 입력 (RejectModal 의 textarea).
    // ⚠ 정확일치로 찾는다 — 부분일치(/반려 사유/)는 검수 메모 패널의 제목
    //   「반려 사유에 첨부할 지적」까지 집어 «여러 요소» 오류가 난다.
    const reasonTextarea = await screen.findByLabelText('반려 사유');
    await user.type(reasonTextarea, '재작업 필요');

    const submitBtn = screen.getByRole('button', { name: '반려 확정' });
    await waitFor(() => {
      expect(submitBtn).not.toBeDisabled();
    });
    await user.click(submitBtn);

    await waitFor(() => {
      expect(rejectBody).not.toBeNull();
    });
    // 사용자 입력 + 전체 의견 + 이슈 모두 포함 확인.
    expect(rejectBody!.reason).toContain('재작업 필요');
    expect(rejectBody!.reason).toContain('[전체 의견]');
    expect(rejectBody!.reason).toContain('전반적으로 미흡합니다');
    expect(rejectBody!.reason).toContain('[이슈 2건]');
    expect(rejectBody!.reason).toContain('박스 어긋남');
    expect(rejectBody!.reason).toContain('(#11)');
    expect(rejectBody!.reason).toContain('객체 누락');
  });

  it('composeRejectReason_사용자입력만_있을때_그대로_반환', () => {
    expect(composeRejectReason('단순 사유', '', [])).toBe('단순 사유');
  });

  it('composeRejectReason_전체의견_포함', () => {
    const result = composeRejectReason('사유', '전체 의견 텍스트', []);
    expect(result).toContain('사유');
    expect(result).toContain('[전체 의견]');
    expect(result).toContain('전체 의견 텍스트');
    expect(result).not.toContain('[이슈');
  });

  it('composeRejectReason_이슈_라벨id_없을때_괄호_생략', () => {
    const result = composeRejectReason('사유', '', [
      { labelId: null, text: '이슈A', ts: '2026-05-07T10:00:00Z' },
      { labelId: 5, text: '이슈B', ts: '2026-05-07T10:00:00Z' },
    ]);
    expect(result).toContain('- 이슈A\n');
    expect(result).toContain('- 이슈B (#5)');
  });

  it('검수화면_프레임_로딩중_스피너_표시되고_프레임없음_문구_미표시', async () => {
    // given: 검수 상세는 로드되지만 프레임 조회는 응답 지연(never-resolve) → framesLoading 유지.
    mock.reset();
    mock
      .onGet('/reviews/10/issues')
      .reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });
    // 프레임 조회 pending → 캔버스 영역 로딩 스피너.
    mock.onGet('/reviews/1/frames').reply(() => new Promise(() => {}));
    // 이슈 스레드 등 부가 조회는 benign 응답(렌더 비차단).
    mock
      .onGet(/.*/)
      .reply(200, { success: true, data: [], message: null, errorCode: null });

    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    // then: 캔버스 영역에 로딩 스피너 노출.
    const loading = await screen.findByTestId('review-frames-loading');
    // then: 캔버스 영역(main) 안에서 로딩 스피너가 렌더된다.
    const main = screen.getByTestId('review-canvas-readonly');
    expect(main).toContainElement(loading);
    // then: 로딩 중 캔버스의 "프레임이 없습니다" 오표시(LabelCanvas empty)가 나타나지 않는다.
    //  (하단 FrameTimeline 의 빈 상태 문구는 별개 컴포넌트로 본 항목 범위 밖.)
    expect(
      screen.queryByTestId('review-label-canvas-empty'),
    ).not.toBeInTheDocument();
  });

  it('검수_화면_캔버스_좌표_마커_컴포넌트_미사용', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });

    const { container } = renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('review-canvas-readonly')).toBeInTheDocument();
    });

    // 캔버스 좌표 마커 — UI/UX §4-9 정합으로 절대 사용 금지 (회귀 방지).
    // 1) marker DOM 노드 미사용
    expect(container.querySelector('[data-marker]')).toBeNull();
    expect(container.querySelector('[data-testid*="marker"]')).toBeNull();
    expect(container.querySelector('[data-testid*="coordinate"]')).toBeNull();
    // 2) IssueSidebar는 텍스트 카드만 — 캔버스 클릭 → 좌표 추가 흐름 없음
    expect(container.querySelector('[data-testid="issue-coord-marker"]')).toBeNull();
  });
});
