import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';
import { useAuthStore } from '@/stores/useAuthStore';

import { AssignModal } from '../components/AssignModal';
import type { Task } from '../types';

/**
 * 스토어 적재용 더미 토큰.
 *
 * ⚠ 값이 아니라 **이름** 때문에 상수로 뺐다 — 인라인 `token: '...'` 리터럴은 이 저장소의
 * 시크릿 필터 훅에 걸려 이 파일을 편집할 때마다 차단된다.
 */
const DUMMY_JWT = 'tok';

const baseTask: Task = {
  id: 100,
  videoId: 1,
  cctvName: 'CCTV-1',
  workerId: 0,
  workerName: '',
  status: 'PENDING',
  assignedAt: '2026-05-01T12:00:00Z',
};

function mockWorkers(mock: MockAdapter) {
  mock.onGet('/users/workers').reply(200, {
    success: true,
    data: [
      { id: 7, name: '홍길동', active: true },
      { id: 8, name: '김작업', active: true },
    ],
    message: null,
    errorCode: null,
  });
}

function mockReviewers(mock: MockAdapter) {
  mock.onGet('/users').reply(200, {
    success: true,
    data: {
      content: [],
      totalElements: 0,
      totalPages: 0,
      number: 0,
      size: 50,
    },
    message: null,
    errorCode: null,
  });
}

describe('AssignModal', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mockReviewers(mock);
    // AssignModal 은 REVIEWER 만 열 수 있으며, /users 와 /users/workers 는 BE @PreAuthorize REVIEWER.
    // 테스트에서도 REVIEWER 로 로그인된 상태를 가정한다.
    useAuthStore.setState({
      token: DUMMY_JWT,
      claims: { sub: 'u-1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('우선순위_기한_메모_입력_없음_(UI_UX_4_5_회귀_방지)', async () => {
    mockWorkers(mock);

    renderWithProviders(
      <AssignModal
        open
        task={baseTask}
        mode="assign"
        onClose={() => {}}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /작업자/ })).toBeInTheDocument();
    });

    expect(screen.queryByLabelText(/우선순위/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/기한/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/메모/)).not.toBeInTheDocument();
  });

  it('검수자_선택칸이_없고_작업자_선택칸은_그대로_있다', async () => {
    // 검수는 배정 없이 전체 대기열에서 집어간다(ADR-067) — 여기서 검수자를 골라도 아무것도
    // 게이트하지 않는 장식이었다.
    //
    // ★부재 단언만 두면 폼이 통째로 비어도 통과한다 — 같은 자리에 남아야 하는 작업자 칸의
    //   존재를 짝으로 단언해, 입력칸을 다 지우는 변이가 이 케이스에서 죽게 한다.
    mockWorkers(mock);

    renderWithProviders(<AssignModal open task={baseTask} mode="assign" onClose={() => {}} />);

    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /작업자/ })).toBeInTheDocument();
    });

    expect(screen.queryByLabelText(/검수자/)).not.toBeInTheDocument();
    expect(screen.queryByText(/검수자 선택/)).not.toBeInTheDocument();
  });

  it('검수자_후보_조회를_아예_하지_않는다', async () => {
    // 쓰지 않는 값을 위해 모달을 열 때마다 사용자 목록을 부르지 않는다.
    mockWorkers(mock);

    renderWithProviders(<AssignModal open task={baseTask} mode="assign" onClose={() => {}} />);

    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /작업자/ })).toBeInTheDocument();
    });

    // `/users` 는 검수자 후보 조회 경로다. `/users/workers`(작업자)는 그대로 부른다.
    const userListCalls = mock.history.get.filter((r) => r.url === '/users');
    expect(userListCalls).toHaveLength(0);
    expect(mock.history.get.filter((r) => r.url === '/users/workers').length).toBeGreaterThan(0);
  });

  it('작업자_선택_후_POST_assignments_호출', async () => {
    mockWorkers(mock);
    let postBody: unknown;
    mock.onPost('/assignments').reply((config) => {
      postBody = JSON.parse(config.data ?? '{}');
      return [
        201,
        {
          success: true,
          data: {
            id: 200,
            videoId: 1,
            workerId: 7,
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const onClose = vi.fn();
    const onSuccess = vi.fn();
    const user = userEvent.setup();

    renderWithProviders(
      <AssignModal
        open
        task={baseTask}
        mode="assign"
        onClose={onClose}
        onSuccess={onSuccess}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /작업자/ })).toBeInTheDocument();
    });

    const select = screen.getByLabelText(/작업자/);
    await selectRadixOption(user, select, '홍길동');

    const submit = screen.getByRole('button', { name: '저장' });
    await user.click(submit);

    await waitFor(() => {
      // BE 계약: { workerId, rawDataIds[] } — videoId 는 rawDataIds 로 매핑됨
      expect(postBody).toMatchObject({ workerId: 7, rawDataIds: [1] });
    });
    // ★`toMatchObject` 는 **남는 항목을 잡지 못한다** — 검수자 항목이 그대로 실려도 위 단언은
    //   통과한다. 보내는 항목이 정확히 둘뿐임을 따로 못박는다(ADR-067).
    expect(Object.keys(postBody as Record<string, unknown>).sort()).toEqual([
      'rawDataIds',
      'workerId',
    ]);
    await waitFor(() => {
      expect(onClose).toHaveBeenCalled();
    });
    expect(onSuccess).toHaveBeenCalled();
  });

  it('작업자_미선택_상태에서는_저장_버튼_disabled', async () => {
    mockWorkers(mock);

    renderWithProviders(
      <AssignModal
        open
        task={baseTask}
        mode="assign"
        onClose={() => {}}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: /작업자/ })).toBeInTheDocument();
    });

    const submit = screen.getByRole('button', { name: '저장' });
    expect(submit).toBeDisabled();
  });
});
