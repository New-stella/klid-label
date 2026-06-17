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

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /상세/ })).toBeInTheDocument();
  });

  it('REVIEWER로_접속하면_각_영상_행에_배정_버튼이_노출된다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    expect(
      screen.getByRole('button', { name: '강남대로 CCTV 작업자 배정' }),
    ).toBeInTheDocument();
  });

  it('배정된_영상은_배정자명이_컬럼에_표시된다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock, { content: [ASSIGNED_VIDEO] });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    // 배정자 컬럼에 workerName 표시
    expect(screen.getByText('김작업')).toBeInTheDocument();
  });

  it('미배정_영상은_미배정으로_표시되고_배정_버튼이_노출된다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock, { content: [UNASSIGNED_VIDEO] });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    expect(screen.getByText('미배정')).toBeInTheDocument();
    // 미배정 → "배정" 버튼 (재배정 아님)
    expect(
      screen.getByRole('button', { name: '강남대로 CCTV 작업자 배정' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '강남대로 CCTV 작업자 재배정' }),
    ).not.toBeInTheDocument();
  });

  it('배정된_영상은_재배정_버튼이_노출된다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock, { content: [ASSIGNED_VIDEO] });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

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

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

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
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

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
    const workerSelect = within(dialog).getByLabelText(/작업자/) as HTMLSelectElement;
    await waitFor(() => {
      expect(workerSelect.value).toBe('5');
    });
    expect(within(dialog).getByText(/김작업.*\(현재\)/)).toBeInTheDocument();
  });

  it('배정_성공후_목록이_refetch되어_행이_갱신된다', async () => {
    // 배정 mutation 의 VIDEO_KEYS 무효화 → /videos 재조회로 행이 갱신되는지 검증.
    // 첫 응답=미배정, 배정 POST 성공 후 재조회=배정됨(김작업).
    setRole('REVIEWER');
    let videosCall = 0;
    mock.onGet('/videos').reply(() => {
      videosCall += 1;
      const content = videosCall === 1 ? [UNASSIGNED_VIDEO] : [ASSIGNED_VIDEO];
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
    mockAssignModalUsers(mock, [{ id: 5, name: '김작업', active: true }]);
    mock.onPost('/assignments').reply(201, {
      success: true,
      data: { id: 77, videoId: 1, workerId: 5, status: 'IN_PROGRESS', assignedAt: '2026-05-02T09:00:00Z' },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('미배정')).toBeInTheDocument();
    });

    await user.click(
      screen.getByRole('button', { name: '강남대로 CCTV 작업자 배정' }),
    );
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
    // 작업자 선택 후 저장
    const dialog = screen.getByRole('dialog');
    const workerSelect = within(dialog).getByLabelText(/작업자/) as HTMLSelectElement;
    await user.selectOptions(workerSelect, '5');
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    // 배정 성공 → VIDEO_KEYS 무효화 → /videos 재조회 → 배정자명 표시
    await waitFor(() => {
      expect(screen.getByText('김작업')).toBeInTheDocument();
    });
    expect(mock.history.post.length).toBeGreaterThanOrEqual(1);
  });

  it('배정_성공후_assignments_쿼리가_무효화된다', async () => {
    // TaskListPage onSuccess 정합 — 배정/재배정 성공 시 ['assignments'] 무효화로
    // 작업 목록(/assignments) 캐시까지 갱신한다.
    setRole('REVIEWER');
    mockVideosOnce(mock, { content: [UNASSIGNED_VIDEO] });
    mockAssignModalUsers(mock, [{ id: 5, name: '김작업', active: true }]);
    mock.onPost('/assignments').reply(201, {
      success: true,
      data: {
        id: 77,
        videoId: 1,
        workerId: 5,
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
      initialEntries: ['/video/completed'],
      queryClient,
    });

    await waitFor(() => {
      expect(screen.getByText('미배정')).toBeInTheDocument();
    });
    await user.click(
      screen.getByRole('button', { name: '강남대로 CCTV 작업자 배정' }),
    );
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
    const dialog = screen.getByRole('dialog');
    const workerSelect = within(dialog).getByLabelText(/작업자/) as HTMLSelectElement;
    await user.selectOptions(workerSelect, '5');
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['assignments'] });
    });
  });

  it('REVIEWER가_배정_버튼을_누르면_해당_영상이_선택된_채_AssignModal이_열린다', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock);
    // AssignModal 이 REVIEWER 일 때 호출하는 작업자/검수자 API 빈 응답 stub
    mockAssignModalUsers(mock);

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    await user.click(
      screen.getByRole('button', { name: '강남대로 CCTV 작업자 배정' }),
    );

    // AssignModal(단건 신규 배정) 오픈 — 영상명이 모달 내부에 선택된 채 표시된다.
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
    expect(screen.getByText('작업 배정')).toBeInTheDocument();
    // 모달 안에 대상 영상명(미리보기)이 표시
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('강남대로 CCTV')).toBeInTheDocument();
  });

  it('WORKER로_접속하면_배정_버튼이_노출되지_않는다', async () => {
    setRole('WORKER');
    mockVideosOnce(mock);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

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
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

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
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

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

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('해당하는 영상이 없습니다.')).toBeInTheDocument();
    });
  });

  it('loading_상태에서_skeleton_렌더', () => {
    setRole('WORKER');
    mock.onGet('/videos').reply(() => new Promise(() => {}));

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    expect(screen.queryByText('해당하는 영상이 없습니다.')).not.toBeInTheDocument();
  });

  it('영상_목록_검색_필터_URL_파라미터_동기화', async () => {
    setRole('WORKER');
    mockVideosOnce(mock, { content: [], total: 0 });

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

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
});
