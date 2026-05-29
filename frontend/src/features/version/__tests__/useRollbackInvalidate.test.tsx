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
      data: { lblHstrySn: 9001, srcSn: 241, versionHash: 'bbb222', registeredUserNo: 42, registeredAt: '2026-05-29T09:00:00Z' },
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

  it('롤백_성공_후_LABEL_KEYS_all과_VERSION_KEYS_all도_invalidate', async () => {
    // 회귀 가드 — byVideo prefix 매칭 의존을 제거하고 LABEL_KEYS.all 로 일괄 무효화
    // 하는지 검증. useLabels 의 byFrame queryKey 가 byVideo prefix 와 정확히 일치
    // 하지 않는 경우(videoId/srcSn 인자 누락 등) 에도 캔버스가 새 라벨로 갱신되도록
    // 안전 가드를 둔다.
    mock.onPost('/versions/bbb222/rollback').reply(200, {
      success: true,
      data: { lblHstrySn: 9001, srcSn: 241, versionHash: 'bbb222', registeredUserNo: 42, registeredAt: '2026-05-29T09:00:00Z' },
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

    const calls = invalidate.mock.calls.map((c) => c[0]);
    expect(calls).toContainEqual({ queryKey: LABEL_KEYS.all });
    expect(calls).toContainEqual({ queryKey: VERSION_KEYS.all });
  });
});
