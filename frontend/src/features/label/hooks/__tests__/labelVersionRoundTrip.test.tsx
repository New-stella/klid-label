// H-ISSUE-41 — 라벨 저장 낙관적 동시성 토큰 왕복 회귀 테스트.
//
// 배경: BE 는 GET /v1/frames/{srcSn}/labels 응답에 labelVersion 을 항상 실어 보내고, PUT 에
//   그 값이 오면 stale 여부를 검증(409)한다. 그런데 FE getLabels() 가 응답을 재조립하면서
//   labelVersion 을 반환 객체에 매핑하지 않아 캐시에 undefined 로 들어갔고, 저장 훅이
//   `cached?.labelVersion ?? options.labelVersion` 으로 읽어도 결국 undefined → PUT body 에
//   필드 자체가 빠져 BE 가 검증을 skip 했다. 즉 <b>동시 편집 보호가 통째로 비활성</b>이었다.
//
// 이 테스트는 조회→저장 파이프라인 전체(getLabels → React Query 캐시 → useUpdateLabels →
// putLabels)를 통과시켜, 매핑이 다시 빠지면 실패한다.

import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';

import type { Label } from '../../types';
import { useLabels } from '../useLabels';
import { useUpdateLabels } from '../useUpdateLabels';

const SRC_SN = 4321;

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

function newClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });
}

function createWrapper(qc: QueryClient) {
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  Wrapper.displayName = 'TestQueryWrapper';
  return Wrapper;
}

describe('라벨셋 버전 왕복 (조회 → 저장)', () => {
  let mock: MockAdapter;
  let sentVersion: unknown;

  beforeEach(() => {
    sentVersion = 'NOT_SENT';
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  function armPut() {
    mock.onPut(`/frames/${SRC_SN}/labels`).reply((config) => {
      const body = JSON.parse(config.data as string) as { labelVersion?: number };
      sentVersion = 'labelVersion' in body ? body.labelVersion : 'NOT_SENT';
      return [
        200,
        {
          success: true,
          data: { frameNo: 1, srcSn: SRC_SN, labelVersion: 43, siblings: [], labels: sample },
          message: null,
          errorCode: null,
        },
      ];
    });
  }

  it('저장_PUT이_조회에서_받은_버전을_body에_포함한다', async () => {
    // given: 조회 응답이 labelVersion=42
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, {
      success: true,
      data: { frameNo: 1, srcSn: SRC_SN, labelVersion: 42, siblings: [], labels: [] },
      message: null,
      errorCode: null,
    });
    armPut();

    const qc = newClient();
    const { result } = renderHook(
      () => ({ query: useLabels(SRC_SN), save: useUpdateLabels(SRC_SN) }),
      { wrapper: createWrapper(qc) },
    );

    // when: 조회가 캐시에 반영된 뒤 저장
    await waitFor(() => expect(result.current.query.isSuccess).toBe(true));
    await act(async () => {
      await result.current.save.mutateAsync(sample);
    });

    // then: 조회가 준 버전이 그대로 되돌아가야 BE 가 stale 저장을 409 로 막을 수 있다
    expect(sentVersion).toBe(42);
  });

  it('버전_없는_응답이면_저장이_필드를_생략해_하위호환을_지킨다', async () => {
    // given: labelVersion 이 없는(레거시) 조회 응답
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, {
      success: true,
      data: { frameNo: 1, srcSn: SRC_SN, siblings: [], labels: [] },
      message: null,
      errorCode: null,
    });
    armPut();

    const qc = newClient();
    const { result } = renderHook(
      () => ({ query: useLabels(SRC_SN), save: useUpdateLabels(SRC_SN) }),
      { wrapper: createWrapper(qc) },
    );

    await waitFor(() => expect(result.current.query.isSuccess).toBe(true));
    await act(async () => {
      await result.current.save.mutateAsync(sample);
    });

    // then: null 은 필드 생략(BE 검사 skip 경로) — 400 을 유발하는 null 전송이 아니어야 한다
    expect(sentVersion).toBe('NOT_SENT');
  });
});
