import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import * as api from '../../api';
import { useAutolabel } from '../useAutolabel';

afterEach(() => {
  vi.restoreAllMocks();
});

const okResponse: api.AutolabelResponse = {
  srcSn: 5001,
  savedCount: 2,
  message: null,
  labels: [
    { lblSn: 1, labelId: 10, label: 'person', points: [1, 2, 3, 4], score: 0.9, trackId: 3 },
    { lblSn: 2, labelId: null, label: 'car', points: [5, 6, 7, 8], score: 0.8, trackId: null },
  ],
};

describe('useAutolabel', () => {
  it('오토라벨_요청시_srcSn으로_호출하고_응답반환', async () => {
    const spy = vi.spyOn(api, 'requestAutolabel').mockResolvedValue(okResponse);
    const { result } = renderHook(() => useAutolabel(5001));

    const out: { value: api.AutolabelResponse | null } = { value: null };
    await act(async () => {
      out.value = await result.current.autolabel();
    });

    expect(spy).toHaveBeenCalledWith(5001);
    expect(out.value?.savedCount).toBe(2);
  });

  it('classIds_지정시_requestAutolabel에_classIds_전달', async () => {
    const spy = vi.spyOn(api, 'requestAutolabel').mockResolvedValue(okResponse);
    const { result } = renderHook(() => useAutolabel(5001));

    await act(async () => {
      await result.current.autolabel(['person', 'car']);
    });

    expect(spy).toHaveBeenCalledWith(5001, ['person', 'car']);
  });

  it('classIds_미지정(전체)시_인자없이_호출_무회귀', async () => {
    const spy = vi.spyOn(api, 'requestAutolabel').mockResolvedValue(okResponse);
    const { result } = renderHook(() => useAutolabel(5001));

    await act(async () => {
      await result.current.autolabel();
    });

    // 인자 없이 호출 — 기존 시그니처(srcSn 단독) 유지.
    expect(spy).toHaveBeenCalledWith(5001);
  });

  it('srcSn_미지정시_요청안하고_null반환', async () => {
    const spy = vi.spyOn(api, 'requestAutolabel');
    const { result } = renderHook(() => useAutolabel(undefined));

    const out: { value: api.AutolabelResponse | null } = { value: null };
    await act(async () => {
      out.value = await result.current.autolabel();
    });

    expect(spy).not.toHaveBeenCalled();
    expect(out.value).toBeNull();
  });

  it('진행중_재요청은_무시되어_한번만_호출', async () => {
    let resolveFn: (v: api.AutolabelResponse) => void = () => {};
    const spy = vi
      .spyOn(api, 'requestAutolabel')
      .mockReturnValue(new Promise((r) => (resolveFn = r)));
    const { result } = renderHook(() => useAutolabel(5001));

    let first: Promise<api.AutolabelResponse | null> = Promise.resolve(null);
    let second: api.AutolabelResponse | null = okResponse;
    await act(async () => {
      first = result.current.autolabel(); // 진행 시작(미완)
      second = await result.current.autolabel(); // 진행 중 → 무시
    });

    expect(second).toBeNull();
    expect(spy).toHaveBeenCalledTimes(1);

    await act(async () => {
      resolveFn(okResponse);
      await first;
    });
  });

  it('mock_응답은_message포함_그대로_반환되어_FE가_차단_판단', async () => {
    // 내부 mock → BE 가 ApiResponse.message 세팅 + savedCount 0. FE 는 message 유무로 경고 분기.
    vi.spyOn(api, 'requestAutolabel').mockResolvedValue({
      ...okResponse,
      savedCount: 0,
      message: 'AI 모델 미로드 — 결과 신뢰 불가',
      labels: [],
    });
    const { result } = renderHook(() => useAutolabel(5001));

    const out: { value: api.AutolabelResponse | null } = { value: null };
    await act(async () => {
      out.value = await result.current.autolabel();
    });

    expect(out.value?.message).toBe('AI 모델 미로드 — 결과 신뢰 불가');
    expect(out.value?.savedCount).toBe(0);
  });
});
