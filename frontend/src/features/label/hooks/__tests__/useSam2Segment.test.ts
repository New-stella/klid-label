import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useSam2Segment } from '../useSam2Segment';
import * as api from '../../api';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('useSam2Segment', () => {
  it('클릭시_포인트_프롬프트로_segment_요청', async () => {
    const spy = vi.spyOn(api, 'requestSam2Segment').mockResolvedValue({
      polygon: [
        [1, 1],
        [2, 2],
        [1, 2],
      ],
      score: 0.9,
    });
    const { result } = renderHook(() => useSam2Segment(5001));

    const res: { value: api.Sam2SegmentResponse | null } = { value: null };
    await act(async () => {
      res.value = await result.current.segment({ points: [[10, 10]] });
    });

    // 내부(INTERNAL) 경로 단일 호출 — 포털 채널 분기(구 3번째 인자 portalMode)는 폐기됐다.
    expect(spy).toHaveBeenCalledWith(5001, { points: [[10, 10]] });
    expect(res.value?.polygon).toHaveLength(3);
  });

  it('드래그시_박스_프롬프트로_요청', async () => {
    const spy = vi.spyOn(api, 'requestSam2Segment').mockResolvedValue({
      polygon: [
        [1, 1],
        [2, 2],
        [1, 2],
      ],
      score: 0.8,
    });
    const { result } = renderHook(() => useSam2Segment(5001));

    await act(async () => {
      await result.current.segment({ box: [5, 5, 40, 40] });
    });

    expect(spy).toHaveBeenCalledWith(5001, { box: [5, 5, 40, 40] });
  });

  it('진행중_재클릭_무시', async () => {
    let resolveFirst: (v: api.Sam2SegmentResponse) => void = () => {};
    const spy = vi.spyOn(api, 'requestSam2Segment').mockImplementation(
      () =>
        new Promise<api.Sam2SegmentResponse>((resolve) => {
          resolveFirst = resolve;
        }),
    );
    const { result } = renderHook(() => useSam2Segment(5001));

    // 첫 요청 시작 (미해결 상태 유지)
    let firstPromise: Promise<api.Sam2SegmentResponse | null> = Promise.resolve(null);
    act(() => {
      firstPromise = result.current.segment({ points: [[10, 10]] });
    });
    await waitFor(() => expect(result.current.isSegmenting).toBe(true));

    // 진행 중 두 번째 요청 → 무시(null) + API 추가 호출 없음
    let second: api.Sam2SegmentResponse | null = { polygon: [], score: 1 };
    await act(async () => {
      second = await result.current.segment({ points: [[20, 20]] });
    });
    expect(second).toBeNull();
    expect(spy).toHaveBeenCalledTimes(1);

    // 첫 요청 해결
    await act(async () => {
      resolveFirst({ polygon: [[1, 1], [2, 2], [1, 2]], score: 0.9 });
      await firstPromise;
    });
    expect(result.current.isSegmenting).toBe(false);
  });

  it('프레임_전환후_도착한_응답_폐기', async () => {
    let resolveReq: (v: api.Sam2SegmentResponse) => void = () => {};
    vi.spyOn(api, 'requestSam2Segment').mockImplementation(
      () =>
        new Promise<api.Sam2SegmentResponse>((resolve) => {
          resolveReq = resolve;
        }),
    );
    const { result, rerender } = renderHook(({ srcSn }) => useSam2Segment(srcSn), {
      initialProps: { srcSn: 5001 },
    });

    let pending: Promise<api.Sam2SegmentResponse | null> = Promise.resolve(null);
    act(() => {
      pending = result.current.segment({ points: [[10, 10]] });
    });
    await waitFor(() => expect(result.current.isSegmenting).toBe(true));

    // 프레임 전환 (srcSn 변경)
    rerender({ srcSn: 9999 });

    // 응답 도착 — 요청 시점(5001) ≠ 현재(9999) → 폐기(null)
    let res: api.Sam2SegmentResponse | null = { polygon: [[1, 1]], score: 1 };
    await act(async () => {
      resolveReq({ polygon: [[1, 1], [2, 2], [1, 2]], score: 0.9 });
      res = await pending;
    });
    expect(res).toBeNull();
  });

  it('srcSn_미지정시_null', async () => {
    const spy = vi.spyOn(api, 'requestSam2Segment');
    const { result } = renderHook(() => useSam2Segment(undefined));
    let res: api.Sam2SegmentResponse | null = { polygon: [], score: 1 };
    await act(async () => {
      res = await result.current.segment({ points: [[10, 10]] });
    });
    expect(res).toBeNull();
    expect(spy).not.toHaveBeenCalled();
  });
});
