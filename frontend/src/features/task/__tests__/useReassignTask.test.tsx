import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';

import { useReassignTask } from '../hooks/useReassignTask';

function wrapper(qc: QueryClient) {
  return function Wrap({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  };
}

describe('useReassignTask', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('재배정시_PATCH_assignments_id_호출', async () => {
    let calledMethod: string | undefined;
    let calledUrl: string | undefined;
    mock.onPatch('/assignments/200').reply((config) => {
      calledMethod = config.method;
      calledUrl = config.url;
      return [
        200,
        {
          success: true,
          data: { id: 200, videoId: 1, workerId: 9, status: 'PENDING', assignedAt: '2026-05-07T10:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const { result } = renderHook(() => useReassignTask(), { wrapper: wrapper(qc) });

    result.current.mutate({ id: 200, body: { workerId: 9 } });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(calledMethod).toBe('patch');
    expect(calledUrl).toBe('/assignments/200');
  });
});
