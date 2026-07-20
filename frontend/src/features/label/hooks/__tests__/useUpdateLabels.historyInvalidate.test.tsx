// useUpdateLabels — 저장 성공 시 라벨 변경 이력(LABEL_KEYS.history*) invalidate (Phase 5 / A-4).
//
// 저장(PUT /frames/{srcSn}/labels)은 BE 가 LS_DATA_LBL_HSTRY 에 ADDED/UPDATED 이력을 기록하므로,
// 저장 성공 직후 히스토리 패널이 새 이력을 반영하려면 해당 프레임 히스토리 캐시를 무효화해야 한다.

import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';
import { LABEL_KEYS } from '@/lib/queryKeys';

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

describe('useUpdateLabels — 저장 후 히스토리 invalidate', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onPut('/frames/321/labels').reply(200, {
      success: true,
      data: { frameNo: 1, srcSn: 321, labels: sample },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => mock.restore());

  it('저장_성공시_히스토리가_invalidate되어_갱신된다', async () => {
    const qc = newClient();
    const spy = vi.spyOn(qc, 'invalidateQueries');

    const { result } = renderHook(() => useUpdateLabels(321), {
      wrapper: createWrapper(qc),
    });

    await result.current.mutateAsync(sample);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // 저장한 프레임(srcSn=321)의 히스토리 전체 페이지 무효화
    expect(spy).toHaveBeenCalledWith({
      queryKey: LABEL_KEYS.historyByFrame(321),
    });
  });
});
