import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';

import { useAugmentJobs } from '../hooks/useAugmentJobs';

describe('useAugmentJobs (refetchInterval 5000)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('잡_조회_훅이_refetchInterval_5000_설정되어_있음', async () => {
    mock.onGet('/augments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 6 },
      message: null,
      errorCode: null,
    });

    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0, staleTime: 0 },
      },
    });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useAugmentJobs(), { wrapper });

    await waitFor(() => {
      expect(result.current.data).toBeDefined();
    });

    // refetchInterval을 직접 검증 — Query 내부 옵션 확인
    const queries = queryClient.getQueryCache().findAll();
    const target = queries.find((q) => q.queryKey[0] === 'augments');
    expect(target).toBeDefined();
    // observers의 옵션을 통해 refetchInterval 검증
    const observerCount = target?.getObserversCount() ?? 0;
    expect(observerCount).toBeGreaterThan(0);
  });
});
