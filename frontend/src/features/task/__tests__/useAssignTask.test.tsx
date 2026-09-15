import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';
import { ASSIGNMENT_KEYS, TASK_BOARD_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';

import { useAssignTask } from '../hooks/useAssignTask';

function wrapper(qc: QueryClient) {
  return function Wrap({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  };
}

describe('useAssignTask', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('배정시_POST_assignments_호출', async () => {
    let calledMethod: string | undefined;
    let calledUrl: string | undefined;
    mock.onPost('/assignments').reply((config) => {
      calledMethod = config.method;
      calledUrl = config.url;
      return [
        201,
        {
          success: true,
          data: { id: 100, videoId: 1, workerId: 9, status: 'PENDING', assignedAt: '2026-05-07T10:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const { result } = renderHook(() => useAssignTask(), { wrapper: wrapper(qc) });

    result.current.mutate({ workerId: 9, rawDataIds: [1] });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(calledMethod).toBe('post');
    expect(calledUrl).toBe('/assignments');
  });

  it('배정_성공시_TASK_BOARD_ASSIGNMENT_VIDEO_캐시_invalidate', async () => {
    // given: 201 응답 + stale 캐시 시드 (TASK_BOARD_KEYS 누락 시 작업 목록이 옛 데이터로 노출되는 회귀 가드)
    mock.onPost('/assignments').reply(201, {
      success: true,
      data: { id: 100, videoId: 1, workerId: 9, status: 'PENDING', assignedAt: '2026-05-07T10:00:00Z' },
      message: null,
      errorCode: null,
    });

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    qc.setQueryData(TASK_BOARD_KEYS.list({ page: 0 }), { content: ['stale-board'] });
    qc.setQueryData(ASSIGNMENT_KEYS.list({ page: 0 }), { content: ['stale-assign'] });
    qc.setQueryData(VIDEO_KEYS.list({ page: 0 }), { content: ['stale-video'] });

    const { result } = renderHook(() => useAssignTask(), { wrapper: wrapper(qc) });

    // when
    result.current.mutate({ workerId: 9, rawDataIds: [1] });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // then
    expect(qc.getQueryState(TASK_BOARD_KEYS.list({ page: 0 }))?.isInvalidated).toBe(true);
    expect(qc.getQueryState(ASSIGNMENT_KEYS.list({ page: 0 }))?.isInvalidated).toBe(true);
    expect(qc.getQueryState(VIDEO_KEYS.list({ page: 0 }))?.isInvalidated).toBe(true);
  });
});
