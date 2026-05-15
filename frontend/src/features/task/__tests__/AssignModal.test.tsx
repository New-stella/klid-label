import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import type { Task } from '../types';

import { AssignModal } from '../components/AssignModal';

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
      token: 'tok',
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
      expect(screen.getByText('홍길동')).toBeInTheDocument();
    });

    expect(screen.queryByLabelText(/우선순위/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/기한/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/메모/)).not.toBeInTheDocument();
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
      expect(screen.getByText('홍길동')).toBeInTheDocument();
    });

    const select = screen.getByLabelText(/작업자/) as HTMLSelectElement;
    await user.selectOptions(select, '7');

    const submit = screen.getByRole('button', { name: '저장' });
    await user.click(submit);

    await waitFor(() => {
      // BE 계약: { workerId, rawDataIds[] } — videoId 는 rawDataIds 로 매핑됨
      expect(postBody).toMatchObject({ workerId: 7, rawDataIds: [1] });
    });
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
      expect(screen.getByText('홍길동')).toBeInTheDocument();
    });

    const submit = screen.getByRole('button', { name: '저장' });
    expect(submit).toBeDisabled();
  });
});
