// useLabels — placeholderData: keepPreviousData 동작 검증.
// 핵심: srcSn 변경 시 isLoading 은 false 유지(이전 data 보존) → LabelingPage 의
// Spinner 분기를 타지 않아 하단 슬라이더가 unmount 되지 않고 재생 인터벌이 유지됨.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { ReactNode } from 'react';
import MockAdapter from 'axios-mock-adapter';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { useLabels } from '@/features/label/hooks/useLabels';
import { createTestQueryClient } from '@/test/renderWithProviders';

function payload(srcSn: number, frameNo: number) {
  return {
    success: true,
    data: {
      frameNo,
      srcSn,
      videoId: 7,
      siblings: [
        { srcSn: 200, frameNo: 0 },
        { srcSn: 201, frameNo: 1 },
        { srcSn: 202, frameNo: 2 },
      ],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

describe('useLabels — placeholderData: keepPreviousData', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/frames/200/labels').reply(200, payload(200, 0));
    mock.onGet('/frames/201/labels').reply(200, payload(201, 1));
  });

  afterEach(() => {
    mock.restore();
  });

  it('첫_fetch_시_isLoading_true_로_시작한다', async () => {
    const qc = createTestQueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useLabels(200), { wrapper });

    expect(result.current.isLoading).toBe(true);

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.data?.srcSn).toBe(200);
  });

  it('srcSn_변경_시_이전_data_유지되고_isLoading_은_false_(keepPreviousData)', async () => {
    const qc = createTestQueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    );

    const { result, rerender } = renderHook(({ id }: { id: number }) => useLabels(id), {
      wrapper,
      initialProps: { id: 200 },
    });

    // 첫 fetch 완료 대기
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.data?.srcSn).toBe(200);

    // srcSn 변경 — 새 쿼리 키로 전환
    act(() => {
      rerender({ id: 201 });
    });

    // 핵심: isLoading 은 false 유지 (이전 data 가 placeholder 로 즉시 노출)
    expect(result.current.isLoading).toBe(false);
    // 이전 데이터(srcSn=200) 가 일시적으로 노출됨
    expect(result.current.data?.srcSn).toBe(200);
    // 백그라운드에서 새 fetch 진행 중
    expect(result.current.isFetching).toBe(true);

    // 새 fetch 완료 후 data 갱신
    await waitFor(() => expect(result.current.data?.srcSn).toBe(201));
    expect(result.current.isFetching).toBe(false);
  });
});
