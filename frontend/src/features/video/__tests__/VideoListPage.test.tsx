import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { VideoListPage } from '@/pages/VideoListPage';
import { useAuthStore } from '@/stores/useAuthStore';
import {
  createTestQueryClient,
  renderWithProviders,
} from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';

const UNASSIGNED_VIDEO = {
  id: 1,
  cctvName: '강남대로 CCTV',
  vmsClipId: 'VMS-1',
  eventName: '쓰러짐',
  eventTypeCd: 'FALL',
  localGov: '강남구',
  frameCount: 900,
  status: 'COMPLETED',
  capturedAt: '2026-05-01T12:00:00Z',
};

// Phase 1 BE: /videos 응답에 LABELER 배정 정보가 포함된 영상.
const ASSIGNED_VIDEO = {
  ...UNASSIGNED_VIDEO,
  assignmentId: 77,
  workerId: 5,
  workerName: '김작업',
  assignedAt: '2026-05-02T09:00:00Z',
  assignStatus: 'IN_PROGRESS',
};

// 검수 완료(작업 종결) 영상 — TaskListPage 정합상 재배정 버튼을 노출하지 않는다.
const COMPLETED_VIDEO = {
  ...ASSIGNED_VIDEO,
  assignStatus: 'COMPLETED',
};

function mockVideosOnce(mock: MockAdapter, opts: { content?: unknown[]; total?: number } = {}) {
  mock.onGet('/videos').reply(200, {
    success: true,
    data: {
      content: opts.content ?? [UNASSIGNED_VIDEO],
      totalElements: opts.total ?? 1,
      totalPages: 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  });
}

// AssignModal 이 REVIEWER 일 때 호출하는 작업자/검수자 API stub.
function mockAssignModalUsers(mock: MockAdapter, workers: unknown[] = []) {
  mock.onGet('/users/workers').reply(200, {
    success: true,
    data: workers,
    message: null,
    errorCode: null,
  });
  mock.onGet('/users').reply(200, {
    success: true,
    data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 50 },
    message: null,
    errorCode: null,
  });
}

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    token: 'fake',
    claims: {
      sub: 'u-1',
      role,
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

describe('VideoListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('영상_목록_렌더_및_상세_버튼_노출', async () => {
    // mock 정합 — 영상 목록에는 행 별 액션으로 '상세' 버튼이 항상 존재한다.
    setRole('REVIEWER');
    mockVideosOnce(mock);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /상세/ })).toBeInTheDocument();
  });

  it('배정된_영상은_배정자명이_컬럼에_표시된다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock, { content: [ASSIGNED_VIDEO] });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    // 배정자 컬럼에 workerName 표시
    expect(screen.getByText('김작업')).toBeInTheDocument();
  });

  it('미배정_영상은_미배정으로_표시되고_재배정_버튼은_노출되지_않는다', async () => {
    // 마킹 개편 후 단건 "배정" 버튼은 제거됨. 미배정 영상(검수완료 상태)은
    // '미배정' 표기만 남고 재배정 버튼은 노출되지 않는다.
    // (미배정 + MARKING_READY → "마킹" 버튼 노출은 pages/__tests__ 가 커버)
    setRole('REVIEWER');
    mockVideosOnce(mock, { content: [UNASSIGNED_VIDEO] });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    expect(screen.getByText('미배정')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '강남대로 CCTV 작업자 재배정' }),
    ).not.toBeInTheDocument();
  });

  it('배정된_영상은_재배정_버튼이_노출된다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock, { content: [ASSIGNED_VIDEO] });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    // 배정된 영상 → "재배정" 버튼
    expect(
      screen.getByRole('button', { name: '강남대로 CCTV 작업자 재배정' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '강남대로 CCTV 작업자 배정' }),
    ).not.toBeInTheDocument();
  });

  it('완료_상태_영상은_재배정_버튼이_노출되지_않는다', async () => {
    // TaskListPage(L654) 정합: 검수 승인 완료(workerId && status===COMPLETED) 행은
    // 재배정 불가 — 버튼 자체를 가린다. BE 가드(ASSIGNMENT_ALREADY_COMPLETED)와 짝.
    setRole('REVIEWER');
    mockVideosOnce(mock, { content: [COMPLETED_VIDEO] });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    // 완료 영상 → 재배정/배정 버튼 모두 미노출
    expect(
      screen.queryByRole('button', { name: '강남대로 CCTV 작업자 재배정' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '강남대로 CCTV 작업자 배정' }),
    ).not.toBeInTheDocument();
    // 상세 버튼은 여전히 노출
    expect(screen.getByRole('button', { name: /상세/ })).toBeInTheDocument();
  });

  it('재배정_버튼_클릭시_현재_배정자가_사전선택된_재배정모달이_열린다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock, { content: [ASSIGNED_VIDEO] });
    mockAssignModalUsers(mock, [
      { id: 5, name: '김작업', active: true },
      { id: 6, name: '박작업', active: true },
    ]);

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    await user.click(
      screen.getByRole('button', { name: '강남대로 CCTV 작업자 재배정' }),
    );

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
    // 재배정 모드 — 제목 + 현재 배정자(workerId=5) 사전선택 + "(현재)" 표기
    expect(screen.getByText('작업 재배정')).toBeInTheDocument();
    const dialog = screen.getByRole('dialog');
    const workerSelect = within(dialog).getByLabelText(/작업자/);
    await waitFor(() => {
      expect(workerSelect).toHaveTextContent('김작업');
    });
    expect(within(dialog).getByText(/김작업.*\(현재\)/)).toBeInTheDocument();
  });

  it('재배정_성공후_목록이_refetch되어_행이_갱신된다', async () => {
    // 마킹 개편으로 단건 배정 경로는 제거됨 — handleAssignDone 의 refetch/무효화는
    // 재배정(reassign) 경로로 여전히 유효하므로 재배정 트리거로 검증한다.
    // 첫 응답=김작업(workerId 5), 재배정 PATCH 성공 후 재조회=박작업(workerId 6).
    setRole('REVIEWER');
    let videosCall = 0;
    mock.onGet('/videos').reply(() => {
      videosCall += 1;
      const content =
        videosCall === 1
          ? [ASSIGNED_VIDEO]
          : [{ ...ASSIGNED_VIDEO, workerId: 6, workerName: '박작업' }];
      return [
        200,
        {
          success: true,
          data: { content, totalElements: 1, totalPages: 1, number: 0, size: 20 },
          message: null,
          errorCode: null,
        },
      ];
    });
    mockAssignModalUsers(mock, [
      { id: 5, name: '김작업', active: true },
      { id: 6, name: '박작업', active: true },
    ]);
    mock.onPatch('/assignments/77').reply(200, {
      success: true,
      data: { id: 77, videoId: 1, workerId: 6, status: 'IN_PROGRESS', assignedAt: '2026-05-02T09:00:00Z' },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('김작업')).toBeInTheDocument();
    });

    await user.click(
      screen.getByRole('button', { name: '강남대로 CCTV 작업자 재배정' }),
    );
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
    // 현재(5)와 다른 작업자(6) 선택 후 저장 (동일 작업자면 저장 버튼 비활성)
    const dialog = screen.getByRole('dialog');
    const workerSelect = within(dialog).getByLabelText(/작업자/);
    await selectRadixOption(user, workerSelect, '박작업');
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    // 재배정 성공 → VIDEO_KEYS 무효화 → /videos 재조회 → 새 배정자명 표시
    await waitFor(() => {
      expect(screen.getByText('박작업')).toBeInTheDocument();
    });
    expect(mock.history.patch.length).toBeGreaterThanOrEqual(1);
  });

  it('재배정_성공후_assignments_쿼리가_무효화된다', async () => {
    // TaskListPage onSuccess 정합 — 재배정 성공 시 ['assignments'] 무효화로
    // 작업 목록(/assignments) 캐시까지 갱신한다(handleAssignDone).
    setRole('REVIEWER');
    mockVideosOnce(mock, { content: [ASSIGNED_VIDEO] });
    mockAssignModalUsers(mock, [
      { id: 5, name: '김작업', active: true },
      { id: 6, name: '박작업', active: true },
    ]);
    mock.onPatch('/assignments/77').reply(200, {
      success: true,
      data: {
        id: 77,
        videoId: 1,
        workerId: 6,
        status: 'IN_PROGRESS',
        assignedAt: '2026-05-02T09:00:00Z',
      },
      message: null,
      errorCode: null,
    });

    const queryClient = createTestQueryClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video/status'],
      queryClient,
    });

    await waitFor(() => {
      expect(screen.getByText('김작업')).toBeInTheDocument();
    });
    await user.click(
      screen.getByRole('button', { name: '강남대로 CCTV 작업자 재배정' }),
    );
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
    const dialog = screen.getByRole('dialog');
    const workerSelect = within(dialog).getByLabelText(/작업자/);
    await selectRadixOption(user, workerSelect, '박작업');
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['assignments'] });
    });
  });

  it('WORKER로_접속하면_배정_버튼이_노출되지_않는다', async () => {
    setRole('WORKER');
    mockVideosOnce(mock);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    // WORKER 에게는 행/일괄 배정 액션이 전혀 노출되지 않는다 (배정은 REVIEWER 전용).
    expect(
      screen.queryByRole('button', { name: /작업자 배정/ }),
    ).not.toBeInTheDocument();
    // 상세 버튼은 역할 무관 항상 노출
    expect(screen.getByRole('button', { name: /상세/ })).toBeInTheDocument();
  });

  it('REVIEWER가_영상을_선택하면_일괄_배정_버튼이_노출되고_누르면_일괄_AssignModal이_열린다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock);
    mock.onGet('/users/workers').reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 50 },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });

    // 행 체크박스 선택 → 일괄 액션 바에 배정 버튼 노출
    await user.click(screen.getByRole('checkbox', { name: '강남대로 CCTV 선택' }));
    const bulkBtn = await screen.findByRole('button', { name: /일괄 배정/ });
    expect(bulkBtn).toBeInTheDocument();

    await user.click(bulkBtn);
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
    // 모달 제목이 일괄 배정 모드(N개 영상 일괄 배정)임을 확인 (heading 로 한정)
    expect(
      screen.getByRole('heading', { name: /개 영상 일괄 배정/ }),
    ).toBeInTheDocument();
  });

  it('WORKER가_영상을_선택해도_일괄_배정_액션바가_노출되지_않는다', async () => {
    // CWE-285 심층 방어 — 배정은 REVIEWER 전용이므로 WORKER 에겐
    // 일괄 액션 바(선택 N건 + 일괄 배정 버튼) 자체를 노출하지 않는다.
    setRole('WORKER');
    mockVideosOnce(mock);

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    await user.click(screen.getByRole('checkbox', { name: '강남대로 CCTV 선택' }));

    // 일괄 배정 버튼 미노출
    expect(
      screen.queryByRole('button', { name: /일괄 배정/ }),
    ).not.toBeInTheDocument();
    // 액션 바 컨테이너(선택 N건 텍스트)도 미노출 — 액션 바 자체가 렌더되지 않음
    expect(screen.queryByText(/선택 \d+건/)).not.toBeInTheDocument();
  });

  it('영상_없을_때_EmptyState_노출', async () => {
    setRole('WORKER');
    mockVideosOnce(mock, { content: [], total: 0 });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('해당하는 영상이 없습니다.')).toBeInTheDocument();
    });
  });

  it('loading_상태에서_skeleton_렌더', () => {
    setRole('WORKER');
    mock.onGet('/videos').reply(() => new Promise(() => {}));

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    expect(screen.queryByText('해당하는 영상이 없습니다.')).not.toBeInTheDocument();
  });

  it('영상_목록_검색_필터_URL_파라미터_동기화', async () => {
    setRole('WORKER');
    mockVideosOnce(mock, { content: [], total: 0 });

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    // mock 정합 — 라벨이 'CCTV명 / 영상ID'로 변경됨
    await waitFor(() => {
      expect(screen.getByLabelText('CCTV명 / 영상ID')).toBeInTheDocument();
    });

    const input = screen.getByLabelText('CCTV명 / 영상ID');
    await user.type(input, '강남');
    await user.click(screen.getByRole('button', { name: /조회/ }));

    await waitFor(() => {
      const last = mock.history.get[mock.history.get.length - 1];
      expect(last?.params).toMatchObject({ cctvNameKeyword: '강남' });
    });
  });

  // Phase 3 — 비식별 상태 배지 (deidentStatus 우선 표시, AC3-FE)
  it('비식별_진행중_영상은_목록에서_비식별진행중_배지가_표시된다', async () => {
    setRole('WORKER');
    // status=COMPLETED 여도 deidentStatus 가 우선해 '비식별 진행중' 을 표시한다(precedence).
    mockVideosOnce(mock, {
      content: [
        { ...UNASSIGNED_VIDEO, status: 'COMPLETED', deidentStatus: 'IN_PROGRESS', deIdntfYn: 'N' },
      ],
    });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    // 처리단계 배지는 테이블 행 범위로 한정해 조회(상태 필터 드롭다운의 '완료' 옵션과 구분).
    const row = screen.getByText('강남대로 CCTV').closest('tr');
    const scoped = within(row ?? document.body);
    expect(scoped.getByText('비식별 진행중')).toBeInTheDocument();
    // dataSttsCd 기반 '완료' 배지보다 우선 → 행 내 '완료' 미노출
    expect(scoped.queryByText('완료')).not.toBeInTheDocument();
  });

  it('비식별_실패_영상은_비식별실패_배지가_표시된다', async () => {
    setRole('WORKER');
    mockVideosOnce(mock, {
      content: [
        { ...UNASSIGNED_VIDEO, status: 'COMPLETED', deidentStatus: 'FAILED', deIdntfYn: 'F' },
      ],
    });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    const row = screen.getByText('강남대로 CCTV').closest('tr');
    const scoped = within(row ?? document.body);
    expect(scoped.getByText('비식별 실패')).toBeInTheDocument();
    expect(scoped.queryByText('완료')).not.toBeInTheDocument();
  });

  it('비식별_완료(DONE)_영상은_기존_dataSttsCd_배지가_표시된다', async () => {
    setRole('WORKER');
    mockVideosOnce(mock, {
      content: [
        { ...UNASSIGNED_VIDEO, status: 'COMPLETED', deidentStatus: 'DONE', deIdntfYn: 'Y' },
      ],
    });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    const row = screen.getByText('강남대로 CCTV').closest('tr');
    const scoped = within(row ?? document.body);
    // DONE → 기존 StageBadge('완료') 유지, 비식별 배지는 미노출
    expect(scoped.getByText('완료')).toBeInTheDocument();
    expect(scoped.queryByText('비식별 진행중')).not.toBeInTheDocument();
    expect(scoped.queryByText('비식별 실패')).not.toBeInTheDocument();
  });

  // R1 — 개인정보 유무 컬럼 제거.
  // 관제서버가 개인정보 유무를 실제로 보내지 않고(LS_DATA_INGEST 3필드 전부 NULL),
  // 화면이 보던 privacyTypeCd 는 적재 시 고정되는 레거시 컬럼이라 목록에서 제거한다.
  it('영상_목록에_개인정보_컬럼이_렌더되지_않는다', async () => {
    setRole('WORKER');
    // privacyTypeCd 가 내려와도(BE 응답 계약 무변경) 화면에는 노출하지 않는다.
    mockVideosOnce(mock, {
      content: [{ ...UNASSIGNED_VIDEO, privacyTypeCd: 'PRVC' }],
    });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    // 헤더 미노출
    expect(
      screen.queryByRole('columnheader', { name: '개인정보' }),
    ).not.toBeInTheDocument();
    // 배지 미노출 (구 개인정보 등급 라벨: 개인정보 / 가명처리 / 비식별)
    expect(screen.queryByText('개인정보')).not.toBeInTheDocument();
    expect(screen.queryByText('가명처리')).not.toBeInTheDocument();
    expect(screen.queryByText('비식별')).not.toBeInTheDocument();
  });

  it('빈_목록_상태의_colSpan이_헤더_칸수와_같다', async () => {
    // 컬럼 제거 시 헤더만 지우고 colSpan 을 두면 빈 상태 셀이 테이블 폭을 못 채운다.
    setRole('WORKER');
    mockVideosOnce(mock, { content: [], total: 0 });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => {
      expect(screen.getByText('해당하는 영상이 없습니다.')).toBeInTheDocument();
    });
    const headerCount = screen.getAllByRole('columnheader').length;
    const emptyCell = screen.getByText('해당하는 영상이 없습니다.').closest('td');
    expect(emptyCell?.getAttribute('colspan')).toBe(String(headerCount));
  });

  it('로딩_스켈레톤_칸수가_헤더_칸수와_같다', () => {
    // 컬럼 제거 시 스켈레톤 칸수를 안 고치면 로딩 중 레이아웃이 헤더와 어긋난다.
    setRole('WORKER');
    mock.onGet('/videos').reply(() => new Promise(() => {}));

    const { container } = renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video/status'],
    });

    const headerCount = screen.getAllByRole('columnheader').length;
    const firstSkeletonRow = container.querySelector('tbody tr');
    expect(firstSkeletonRow).not.toBeNull();
    expect(firstSkeletonRow?.querySelectorAll('td').length).toBe(headerCount);
  });
});
