// 회귀 가드 — 라벨 저장 성공 후 5개 도메인 캐시(LABEL/VIDEO/ASSIGNMENT/REVIEW/VERSION)가
// 모두 invalidate 되는지 검증.
//
// 증상 1 (HIGH) "저장 후 작업이력에 새 커밋이 바로 안 보임" 의 근본 원인이
// onSuccess 의 VERSION_KEYS 누락이었던 회귀를 방지한다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import {
  ASSIGNMENT_KEYS,
  LABEL_KEYS,
  REVIEW_KEYS,
  VERSION_KEYS,
  VIDEO_KEYS,
} from '@/lib/queryKeys';

import { useUpdateLabels } from '../hooks/useUpdateLabels';
import type { Label } from '../types';

function bbox(id: string): Label {
  return {
    id,
    frameNo: 0,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 20, right: 100, bottom: 80 },
  };
}

describe('useUpdateLabels onSuccess invalidate', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('저장_성공_시_VERSION_KEYS도_invalidate된다', async () => {
    mock.onPut('/frames/241/labels').reply(200, {
      success: true,
      data: { srcSn: 241, frameNo: 0, labels: [], siblings: [] },
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

    const { result } = renderHook(() => useUpdateLabels(241), { wrapper });
    result.current.mutate([bbox('a')]);

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    const calls = invalidate.mock.calls.map((c) => c[0]);
    expect(calls).toContainEqual({ queryKey: VERSION_KEYS.all });
  });

  it('저장_성공_시_LABEL_VIDEO_ASSIGNMENT_REVIEW_VERSION_5종_모두_invalidate', async () => {
    mock.onPut('/frames/241/labels').reply(200, {
      success: true,
      data: { srcSn: 241, frameNo: 0, labels: [], siblings: [] },
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

    const { result } = renderHook(() => useUpdateLabels(241), { wrapper });
    result.current.mutate([bbox('a')]);

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    const calls = invalidate.mock.calls.map((c) => c[0]);
    expect(calls).toContainEqual({ queryKey: LABEL_KEYS.all });
    expect(calls).toContainEqual({ queryKey: VIDEO_KEYS.all });
    expect(calls).toContainEqual({ queryKey: ASSIGNMENT_KEYS.all });
    expect(calls).toContainEqual({ queryKey: REVIEW_KEYS.all });
    expect(calls).toContainEqual({ queryKey: VERSION_KEYS.all });
  });
});
