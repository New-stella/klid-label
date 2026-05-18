import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { LABEL_KEYS, VERSION_KEYS } from '@/lib/queryKeys';

import { useRollback } from '../hooks/useRollback';

describe('useRollback', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('롤백_성공_후_LabelingPage_라벨_재조회_invalidate', async () => {
    mock.onPost('/versions/bbb222/rollback').reply(200, {
      success: true,
      data: { newCommitSha: 'ccc333', rolledBackFrom: 'bbb222' },
      message: null,
      errorCode: null,
    });

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useRollback(777), { wrapper });
    result.current.mutate({ commitSha: 'bbb222', srcSn: 241 });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    // LABEL_KEYS.byVideo + VERSION_KEYS.history 무효화
    const calls = invalidate.mock.calls.map((c) => c[0]);
    expect(calls).toContainEqual({ queryKey: LABEL_KEYS.byVideo(777) });
    expect(calls).toContainEqual({ queryKey: VERSION_KEYS.history(777) });
  });
});
