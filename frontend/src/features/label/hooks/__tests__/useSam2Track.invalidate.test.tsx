// Phase 14 (F3) — SAM2 Track onError invalidate 정밀화 회귀 가드.
//
// 완전 실패(부분결과 0)면 서버 상태가 바뀌지 않았으므로 LABEL_KEYS invalidate 를 skip 하고,
// 부분 실패(이미 서버에 반영된 청크 존재)면 invalidate 로 라벨을 재조회한다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { LABEL_KEYS } from '@/lib/queryKeys';

import { useSam2Track } from '../useSam2Track';
import { type Sam2TrackedItem } from '../../api';

const TRIANGLE: number[][] = [
  [0, 0],
  [10, 0],
  [10, 10],
];

const TRACK_PATH_RE = /\/frames\/(\d+)\/sam2-track/;

function trackedFor(nextSrcSns: number[]): Sam2TrackedItem[] {
  return nextSrcSns.map((s) => ({
    srcSn: s,
    trackId: 't-1',
    label: 'person',
    points: [
      [s, 0],
      [s, 1],
      [s, 2],
    ],
    score: 0.8,
  }));
}

function makeWrapperWithSpy() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return { wrapper, invalidate };
}

describe('useSam2Track onError invalidate 정밀화', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('완전실패_partial_0이면_LABEL_KEYS_invalidate_안함', async () => {
    // given — 첫(그리고 유일한) 청크가 즉시 400 → partial 0
    mock.onPost('/frames/1000/sam2-track').reply(400, {
      success: false,
      data: null,
      message: 'nextSrcSns 검증 실패',
      errorCode: 'INVALID_INPUT',
    });

    const { wrapper, invalidate } = makeWrapperWithSpy();
    const onError = vi.fn();

    // when
    const { result } = renderHook(() => useSam2Track(1000, { onError }), { wrapper });
    result.current.mutate({
      trackId: 't-1',
      prevPolygon: TRIANGLE,
      label: 'person',
      nextSrcSns: [201, 202],
    });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });

    // then — 서버 상태 변화 없음 → invalidate 미호출
    expect(onError).toHaveBeenCalled();
    const invalidatedKeys = invalidate.mock.calls.map((c) => c[0]);
    expect(invalidatedKeys).not.toContainEqual({ queryKey: LABEL_KEYS.all });
  });

  it('부분실패_partial_있으면_invalidate_수행', async () => {
    // given — 80프레임 = 2청크. 청크1(1000) 성공, 청크2(마지막=250) 실패 → partial 50
    const next = Array.from({ length: 80 }, (_, i) => 201 + i); // 201..280
    mock.onPost('/frames/1000/sam2-track').reply((config) => {
      const body = JSON.parse((config.data as string) ?? '{}');
      return [
        200,
        { success: true, data: { tracked: trackedFor(body.nextSrcSns as number[]) }, message: null, errorCode: null },
      ];
    });
    mock.onPost('/frames/250/sam2-track').reply(400, {
      success: false,
      data: null,
      message: 'nextSrcSns 검증 실패',
      errorCode: 'INVALID_INPUT',
    });

    const { wrapper, invalidate } = makeWrapperWithSpy();
    const onError = vi.fn();

    // when
    const { result } = renderHook(() => useSam2Track(1000, { onError }), { wrapper });
    result.current.mutate({
      trackId: 't-1',
      prevPolygon: TRIANGLE,
      label: 'person',
      nextSrcSns: next,
    });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });

    // then — 이미 반영된 청크 있음 → invalidate 수행
    expect(onError).toHaveBeenCalled();
    const invalidatedKeys = invalidate.mock.calls.map((c) => c[0]);
    expect(invalidatedKeys).toContainEqual({ queryKey: LABEL_KEYS.all });
  });

  it('성공시_LABEL_KEYS_invalidate_수행', async () => {
    // given
    mock.onPost(TRACK_PATH_RE).reply((config) => {
      const body = JSON.parse((config.data as string) ?? '{}');
      return [
        200,
        { success: true, data: { tracked: trackedFor(body.nextSrcSns as number[]) }, message: null, errorCode: null },
      ];
    });

    const { wrapper, invalidate } = makeWrapperWithSpy();
    const onSuccess = vi.fn();

    // when
    const { result } = renderHook(() => useSam2Track(1000, { onSuccess }), { wrapper });
    result.current.mutate({
      trackId: 't-1',
      prevPolygon: TRIANGLE,
      label: 'person',
      nextSrcSns: [201, 202],
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    // then
    expect(onSuccess).toHaveBeenCalled();
    const invalidatedKeys = invalidate.mock.calls.map((c) => c[0]);
    expect(invalidatedKeys).toContainEqual({ queryKey: LABEL_KEYS.all });
  });
});
