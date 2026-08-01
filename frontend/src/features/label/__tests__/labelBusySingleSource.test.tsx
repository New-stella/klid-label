// Phase 1 — 4종 장시간 작업이 store busy 하나를 공유하는지(단일 진실원) 훅 레벨 검증.
//   · 저장 진행 중이면 AI 탐지가 시작되지 않는다(배타 실행).
//   · 취소하면 진행 표시가 즉시 풀리고(유령 잠금 없음) 뒤늦게 도착한 저장 응답은 반영되지 않는다.
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '@/features/label/api';
import { useAutolabel } from '@/features/label/hooks/useAutolabel';
import { useUpdateLabels } from '@/features/label/hooks/useUpdateLabels';
import { LABEL_KEYS } from '@/lib/queryKeys';
import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';
import { LabelSource, type Label, type LabelsResponse } from '@/features/label/types';

const SRC_SN = 123;
const INTERNAL_KEY = [...LABEL_KEYS.byFrame(SRC_SN, 0), 'internal'];

const sample: Label[] = [
  {
    id: 'l1',
    frameNo: 0,
    classId: 1,
    className: '사람',
    source: LabelSource.MANUAL,
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
  },
];

function useSaveAndDetect() {
  return {
    save: useUpdateLabels(SRC_SN),
    detect: useAutolabel(SRC_SN),
  };
}

let mock: MockAdapter;
let qc: QueryClient;

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  mock = new MockAdapter(apiClient);
  qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
  useLabelStore.getState().reset();
  useLabelStore.setState({ busy: null, busyGeneration: 0 });
});

afterEach(() => {
  mock.restore();
  vi.restoreAllMocks();
});

describe('라벨링 장시간 작업 busy 단일 진실원', () => {
  it('저장_진행중에는_AI_탐지_요청이_나가지_않는다', async () => {
    // given: 응답이 오지 않는 저장 1건 진행 중
    mock.onPut(`/frames/${SRC_SN}/labels`).reply(() => new Promise(() => {}));
    const detectSpy = vi.spyOn(api, 'requestAutolabel');
    const { result } = renderHook(() => useSaveAndDetect(), { wrapper });
    act(() => {
      void result.current.save.mutateAsync(sample);
    });
    await waitFor(() => expect(result.current.save.isPending).toBe(true));

    // when
    let detected: api.AutolabelResponse | null = null;
    await act(async () => {
      detected = await result.current.detect.autolabel();
    });

    // then: 배타 실행 — 요청 자체가 발화하지 않는다.
    expect(detected).toBeNull();
    expect(detectSpy).not.toHaveBeenCalled();
    expect(result.current.detect.isAutolabeling).toBe(false);
  });

  it('취소하면_저장_진행표시가_즉시_풀리고_뒤늦은_응답은_반영되지_않는다', async () => {
    // given: 저장 진행 중(응답 보류)
    let respond: () => void = () => {};
    mock.onPut(`/frames/${SRC_SN}/labels`).reply(
      () =>
        new Promise((resolve) => {
          respond = () =>
            resolve([
              200,
              {
                success: true,
                data: { frameNo: 0, srcSn: SRC_SN, labels: [], labelVersion: 9 },
                message: null,
                errorCode: null,
              },
            ]);
        }),
    );
    const { result } = renderHook(() => useSaveAndDetect(), { wrapper });
    let saving: Promise<LabelsResponse | null> = Promise.resolve(null);
    act(() => {
      saving = result.current.save.mutateAsync(sample);
    });
    await waitFor(() => expect(result.current.save.isPending).toBe(true));

    // when: 취소(진행 표시는 즉시 풀려야 한다 — 유령 잠금 방지)
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    expect(result.current.save.isPending).toBe(false);

    // then: 뒤늦게 도착한 응답은 캐시 내용에 반영되지 않고 null 로 폐기된다.
    let saved: LabelsResponse | null = null;
    await act(async () => {
      respond();
      saved = await saving;
    });
    expect(saved).toBeNull();
    expect(qc.getQueryData<LabelsResponse>(INTERNAL_KEY)).toBeUndefined();
  });
});
