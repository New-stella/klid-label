// Phase 4 — useSam2Track 재배선: BE 미저장 전환.
//  - invalidateQueries 를 더 이상 호출하지 않는다(자동 refetch 제거).
//  - 성공분(tracked)을 onTracked 로 넘겨 호출측이 작업본에 병합한다.
//  - 부분 실패(partial>0)면 성공분만 onTracked(partial=true) + onError.
//  - stale 가드: 프레임 전환 후 도착한 응답은 폐기(onTracked 미호출).

import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import * as api from '../../api';
import { Sam2TrackChunkError, type Sam2TrackedItem } from '../../api';
import { useSam2Track } from '../useSam2Track';

const TRIANGLE: number[][] = [
  [0, 0],
  [10, 0],
  [10, 10],
];

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

function makeWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return { wrapper, invalidate };
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('useSam2Track — 미저장 병합/stale 가드', () => {
  it('성공시_invalidate_없이_onTracked로_성공분_전달', async () => {
    // 전량 처리 응답 — 끝내지 못한 몫이 없다.
    vi.spyOn(api, 'sam2TrackAllChunks').mockResolvedValue({
      tracked: trackedFor([201, 202]),
      unprocessed: 0,
    });
    const { wrapper, invalidate } = makeWrapper();
    const onTracked = vi.fn();

    const { result } = renderHook(() => useSam2Track(1000, { onTracked }), { wrapper });
    result.current.mutate({ trackId: 't-1', prevPolygon: TRIANGLE, label: 'person', nextSrcSns: [201, 202] });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(onTracked).toHaveBeenCalledWith(expect.any(Array), false);
    expect(onTracked.mock.calls[0][0]).toHaveLength(2);
    expect(invalidate).not.toHaveBeenCalled();
  });

  it('추적_부분실패시_성공분만_병합하고_경고한다', async () => {
    const partial = trackedFor([201, 202]); // 성공분 2프레임
    vi.spyOn(api, 'sam2TrackAllChunks').mockRejectedValue(
      new Sam2TrackChunkError(new Error('chunk fail'), partial, 1, 2),
    );
    const { wrapper, invalidate } = makeWrapper();
    const onTracked = vi.fn();
    const onError = vi.fn();

    const { result } = renderHook(() => useSam2Track(1000, { onTracked, onError }), { wrapper });
    result.current.mutate({ trackId: 't-1', prevPolygon: TRIANGLE, label: 'person', nextSrcSns: [201, 202] });

    await waitFor(() => expect(result.current.isError).toBe(true));
    // 성공분만 partial=true 로 병합 전달 + onError 안내 + invalidate 미호출.
    expect(onTracked).toHaveBeenCalledWith(partial, true);
    expect(onError).toHaveBeenCalled();
    expect(invalidate).not.toHaveBeenCalled();
  });

  it('완전실패(partial_0)면_onTracked_미호출', async () => {
    vi.spyOn(api, 'sam2TrackAllChunks').mockRejectedValue(
      new Sam2TrackChunkError(new Error('all fail'), [], 0, 2),
    );
    const { wrapper } = makeWrapper();
    const onTracked = vi.fn();
    const onError = vi.fn();

    const { result } = renderHook(() => useSam2Track(1000, { onTracked, onError }), { wrapper });
    result.current.mutate({ trackId: 't-1', prevPolygon: TRIANGLE, label: 'person', nextSrcSns: [201, 202] });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(onTracked).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalled();
  });

  it('추적_응답이_프레임전환후_도착하면_폐기된다', async () => {
    // 느린 응답을 수동 제어 — resolve 전에 srcSn 을 바꿔(rerender) stale 로 만든다.
    let resolveFn: (v: api.Sam2TrackRunResult) => void = () => {};
    vi.spyOn(api, 'sam2TrackAllChunks').mockReturnValue(
      new Promise((r) => {
        resolveFn = r;
      }),
    );
    const { wrapper } = makeWrapper();
    const onTracked = vi.fn();

    const { result, rerender } = renderHook(({ id }: { id: number }) => useSam2Track(id, { onTracked }), {
      wrapper,
      initialProps: { id: 1000 },
    });
    result.current.mutate({ trackId: 't-1', prevPolygon: TRIANGLE, label: 'person', nextSrcSns: [201] });

    // 프레임 전환 — 현재 srcSn 이 2000 으로 바뀜.
    rerender({ id: 2000 });
    // 이제 이전 요청(1000) 응답 도착.
    resolveFn({ tracked: trackedFor([201]), unprocessed: 0 });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // 요청 시점 srcSn(1000) !== 현재(2000) → 폐기.
    expect(onTracked).not.toHaveBeenCalled();
  });
});
