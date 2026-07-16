// blocker#2 Phase 3 — 프레임 설명 저장 성공 시 detail 쿼리 invalidate 회귀 가드.
//
// 컴포넌트 테스트는 훅을 전체 mock 하므로 onSuccess→invalidateQueries 배선이 검증되지 않는다.
// 실제 QueryClient 를 사용해 저장 성공 시 detail(srcSn) 쿼리가 무효화(재fetch)됨을 단언한다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { FRAME_DESC_KEYS, useUpdateFrameDescription } from '../useFrameDescription';

describe('useUpdateFrameDescription onSuccess invalidate', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('저장_성공_시_detail_쿼리를_invalidate한다', async () => {
    // given
    mock.onPut('/frames/42/description').reply(200, {
      success: true,
      data: { srcSn: 42, description: '수정된 설명' },
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

    // when
    const { result } = renderHook(() => useUpdateFrameDescription(42), { wrapper });
    result.current.mutate('수정된 설명');

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    // then
    const calls = invalidate.mock.calls.map((c) => c[0]);
    expect(calls).toContainEqual({ queryKey: FRAME_DESC_KEYS.detail(42) });
  });

  it('저장_성공_시_detail_쿼리가_stale로_표시되어_재fetch된다', async () => {
    // given — 실제 QueryClient 캐시에 detail 쿼리를 미리 세팅
    mock.onGet('/frames/42/description').reply(200, {
      success: true,
      data: { srcSn: 42, description: '초기 설명' },
      message: null,
      errorCode: null,
    });
    mock.onPut('/frames/42/description').reply(200, {
      success: true,
      data: { srcSn: 42, description: '수정된 설명' },
      message: null,
      errorCode: null,
    });

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    // detail 쿼리를 fresh 상태로 캐시에 심는다.
    queryClient.setQueryData(FRAME_DESC_KEYS.detail(42), {
      srcSn: 42,
      description: '초기 설명',
    });

    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    // when — 저장 성공
    const { result } = renderHook(() => useUpdateFrameDescription(42), { wrapper });
    result.current.mutate('수정된 설명');

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    // then — invalidate 로 detail 쿼리가 stale(무효) 표시됨
    const state = queryClient.getQueryState(FRAME_DESC_KEYS.detail(42));
    expect(state?.isInvalidated).toBe(true);
  });
});
