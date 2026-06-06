// R8-1 회귀 가드 — 검수 제출(submit) 성공 후 REVIEW 캐시가 invalidate 되는지 검증.
//
// LabelingPage 의 검수제출 버튼 disabled 가드는 useReview(REVIEW_KEYS) 가 반환하는 작업 상태에
// 의존한다. submit 성공 직후 REVIEW_KEYS.all 이 invalidate 되어야 useReview 가 재조회되고
// (ASSIGNED/REJECTED → PENDING) 버튼이 즉시 disabled 로 전환된다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { ASSIGNMENT_KEYS, REVIEW_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';

import { useSubmitReview } from '../hooks/useReviewActions';

describe('useSubmitReview onSuccess invalidate', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('제출_성공_시_REVIEW_VIDEO_ASSIGNMENT_캐시가_invalidate되어_제출버튼_가드가_재평가된다', async () => {
    mock.onPost('/reviews/55/submit').reply(200, {
      success: true,
      data: { videoId: 55, status: 'REVIEW_PENDING' },
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

    const { result } = renderHook(() => useSubmitReview(), { wrapper });
    result.current.mutate(55);

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    const calls = invalidate.mock.calls.map((c) => c[0]);
    // REVIEW_KEYS.all invalidate → useReview 재조회 → 상태 PENDING → submitBlockedByStatus=true → 버튼 disabled
    expect(calls).toContainEqual({ queryKey: REVIEW_KEYS.all });
    expect(calls).toContainEqual({ queryKey: VIDEO_KEYS.all });
    expect(calls).toContainEqual({ queryKey: ASSIGNMENT_KEYS.all });
  });
});
