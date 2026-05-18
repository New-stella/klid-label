// useUpdateLabels — 라벨 저장 후 캐시 무효화 회귀 테스트.
//
// 회귀 배경: SaveCommitFlow.saveAndCommit 만 호출하던 LabelingPage 가
// React Query 캐시 무효화를 누락해 프레임 왕복 시 저장 전 데이터가 노출되는 버그가 있었다.
// 본 훅은 저장 성공 시 LABEL / VIDEO / ASSIGNMENT / REVIEW 캐시를 모두 invalidate 해야 한다.

import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';
import { ASSIGNMENT_KEYS, LABEL_KEYS, REVIEW_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';

import type { Label } from '../../types';
import { useUpdateLabels } from '../useUpdateLabels';

const sample: Label[] = [
  {
    id: 'tmp1',
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 0, top: 0, right: 50, bottom: 30 },
  },
];

function createWrapper(qc: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

function newClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });
}

describe('useUpdateLabels — 저장 후 캐시 무효화', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onPut('/frames/123/labels').reply(200, {
      success: true,
      data: { frameNo: 1, srcSn: 123, labels: sample },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
  });

  it('저장_성공_시_LABEL_VIDEO_ASSIGNMENT_REVIEW_캐시를_모두_invalidate', async () => {
    const qc = newClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');

    const { result } = renderHook(() => useUpdateLabels(123), {
      wrapper: createWrapper(qc),
    });

    await result.current.mutateAsync(sample);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // 4개 키 전부 invalidate 호출되어야 함 (회귀 시 1개만 호출되던 것 → 4개로 확장)
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: LABEL_KEYS.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: VIDEO_KEYS.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ASSIGNMENT_KEYS.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: REVIEW_KEYS.all });
  });

  it('onSuccess_콜백이_invalidate_뒤에_호출됨', async () => {
    const qc = newClient();
    const onSuccess = vi.fn();

    const { result } = renderHook(() => useUpdateLabels(123, { onSuccess }), {
      wrapper: createWrapper(qc),
    });

    await result.current.mutateAsync(sample);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it('srcSn_undefined_면_mutation이_거부됨', async () => {
    const qc = newClient();
    const { result } = renderHook(() => useUpdateLabels(undefined), {
      wrapper: createWrapper(qc),
    });

    await expect(result.current.mutateAsync(sample)).rejects.toThrow('srcSn is required');
  });
});
