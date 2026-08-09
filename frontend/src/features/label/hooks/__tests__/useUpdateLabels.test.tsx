// useUpdateLabels — 라벨 저장 후 캐시 무효화 회귀 테스트.
//
// 회귀 배경: SaveCommitFlow.saveAndCommit 만 호출하던 LabelingPage 가
// React Query 캐시 무효화를 누락해 프레임 왕복 시 저장 전 데이터가 노출되는 버그가 있었다.
// 본 훅은 저장 성공 시 LABEL / VIDEO / ASSIGNMENT / REVIEW 캐시를 모두 invalidate 해야 한다.

import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';
import { ASSIGNMENT_KEYS, LABEL_KEYS, REVIEW_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';

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
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  Wrapper.displayName = 'TestQueryClientWrapper';
  return Wrapper;
}

function newClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });
}

describe('useUpdateLabels — 저장 후 캐시 무효화', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onPut('/frames/123/labels').reply(200, {
      success: true,
      data: { frameNo: 1, srcSn: 123, labels: sample },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
  });

  it('저장_성공_시_LABEL_VIDEO_ASSIGNMENT_REVIEW_캐시를_모두_invalidate', async () => {
    const qc = newClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');

    const { result } = renderHook(() => useUpdateLabels(123), {
      wrapper: createWrapper(qc),
    });

    await result.current.mutateAsync(sample);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // 4개 도메인 키 전부 invalidate 호출되어야 함. 단 LABEL 은 저장한 프레임의 internal
    // 키로 좁힌다 (FE-5 — 포털 캐시 churn 방지).
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: [...LABEL_KEYS.byFrame(123, 0), 'internal'],
    });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: VIDEO_KEYS.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ASSIGNMENT_KEYS.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: REVIEW_KEYS.all });
  });

  it('onSuccess_콜백이_invalidate_뒤에_호출됨', async () => {
    const qc = newClient();
    const onSuccess = vi.fn();

    const { result } = renderHook(() => useUpdateLabels(123, { onSuccess }), {
      wrapper: createWrapper(qc),
    });

    await result.current.mutateAsync(sample);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it('H12_같은_사용자의_연속_저장이_자기자신과_409가_나지_않는다 — 새_labelVersion_즉시반영', async () => {
    // DEV_FIX H12 — 저장 토큰(labelVersion)이 invalidate→refetch(비동기)로만 갱신돼, refetch 완료 전
    //   2회차 저장이 낡은 버전을 보내 자기 자신과 409 를 냈다. 그러면 "다른 사용자가 먼저 저장했습니다"
    //   다이얼로그가 뜨고, '최신 라벨 불러오기' 선택 시 clearDirty() 로 본인 미저장 작업이 소실됐다.
    const qc = newClient();
    const sent: (number | undefined)[] = [];
    let version = 5;
    mock.reset();
    mock.onPut('/frames/123/labels').reply((config) => {
      const body = JSON.parse(config.data as string) as { labelVersion?: number };
      sent.push(body.labelVersion);
      version += 1;
      return [
        200,
        {
          success: true,
          data: { frameNo: 1, srcSn: 123, labels: sample, labelVersion: version },
          message: null,
          errorCode: null,
        },
      ];
    });

    // 조회 응답으로 받은 초기 버전(5)을 폴백으로 전달 — 실제 화면과 동일한 조건.
    const { result } = renderHook(() => useUpdateLabels(123, { labelVersion: 5 }), {
      wrapper: createWrapper(qc),
    });

    await result.current.mutateAsync(sample);
    // 리렌더로 옵션이 갱신되기 전에 곧바로 2회차 저장(연타 시나리오).
    await result.current.mutateAsync(sample);

    // 1회차는 5, 2회차는 서버가 돌려준 6 — 낡은 5 를 다시 보내지 않는다.
    expect(sent).toEqual([5, 6]);
  });

  it('srcSn_undefined_면_mutation이_거부됨', async () => {
    const qc = newClient();
    const { result } = renderHook(() => useUpdateLabels(undefined), {
      wrapper: createWrapper(qc),
    });

    await expect(result.current.mutateAsync(sample)).rejects.toThrow('srcSn is required');
  });
});
