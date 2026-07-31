// DEV_FIX 2차 MED-D — 결과 후처리에 전달되는 "요청 형태"는 **호출별 컨텍스트**여야 한다.
//
// 공유 ref 로 넘기면, 요청 A 가 in-flight 인 동안 트리거된 B(곧 거부되어 아무 결과도 내지 않는다)가
// ref 를 덮어써 A 결과의 안내 문구가 뒤바뀐다(AI 탐지 ↔ AI 분할 오표시).
import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestAutolabel = vi.fn();
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return { ...actual, requestAutolabel: (...args: unknown[]) => requestAutolabel(...args) };
});

import { useAutolabel } from '@/features/label/hooks/useAutolabel';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';
import type { AutolabelResponse } from '@/features/label/api';

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

function response(): AutolabelResponse {
  return { srcSn: 11, savedCount: 0, message: null, labels: [] };
}

describe('useAutolabel — onApply 요청 컨텍스트', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
    useLabelStore.setState({ busy: null, busyGeneration: 0 });
    useUiStore.setState({ toasts: [] });
    requestAutolabel.mockReset();
  });

  it('진행중_요청의_형태는_뒤에_트리거된_요청에_덮이지_않는다', async () => {
    // given: 분할(POLYGON) 요청이 진행 중
    const net = deferred<AutolabelResponse>();
    requestAutolabel.mockReturnValueOnce(net.promise);
    const onApply = vi.fn();
    const { result } = renderHook(() => useAutolabel(11, { onApply }));
    let run: Promise<AutolabelResponse | null> = Promise.resolve(null);
    act(() => {
      run = result.current.autolabel(['person'], 'POLYGON');
    });

    // when: 응답 전에 탐지(BBOX)가 한 번 더 트리거된다(배타 실행에 거부됨) → 그 뒤 첫 응답 도착
    await act(async () => {
      await result.current.autolabel(['car'], 'BBOX');
    });
    await act(async () => {
      net.resolve(response());
      await run;
    });

    // then: 첫 요청의 결과에는 첫 요청의 형태가 함께 전달된다(안내 문구 오표시 방지).
    expect(onApply).toHaveBeenCalledTimes(1);
    expect(onApply.mock.calls[0][1]).toEqual({ shape: 'POLYGON' });
  });
});
