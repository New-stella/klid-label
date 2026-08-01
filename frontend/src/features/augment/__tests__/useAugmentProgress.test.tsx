import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider, type Query } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';
import { AUGMENT_KEYS } from '@/lib/queryKeys';

import { useAugmentProgress } from '../hooks/useAugmentProgress';

/**
 * 진행률 폴링 배선 회귀 — **에러 상태에서 등간격 무한 폴링이 일어나면 안 된다**.
 *
 * 결함: `refetchInterval` 이 `query.state.data` 만 보고 재계산해, 최초 조회가 4xx/5xx 로 실패하면
 * `data` 가 계속 `undefined` 라 기본 주기(5s)로 **탭이 열려 있는 동안 영구 재요청**했다.
 * `retry: false` 는 한 fetch 내부 재시도만 끄므로 이걸 막지 못한다. 서버에 속도 제한(RateLimiter)이
 * 없다는 것이 확정 사항이라 이 증폭을 막는 방어는 화면에만 있다.
 *
 * 검증 방식: 실제 실패 상태의 Query 를 만들고, 훅이 심어둔 `refetchInterval` 콜백을 그 Query 로
 * 호출해 반환값이 `false`(중단)인지 본다 — 5초 타이머를 실제로 기다리지 않으면서 배선을 관측한다.
 */
describe('useAugmentProgress 폴링 배선', () => {
  let mock: MockAdapter;
  const AUG_ID = 7;

  const makeClient = () =>
    new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
    });

  const wrapperOf = (queryClient: QueryClient) =>
    function Wrapper({ children }: { children: ReactNode }) {
      return (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      );
    };

  /** 훅이 심은 refetchInterval 콜백을 현재 Query 상태로 평가한다. */
  const evaluateInterval = (queryClient: QueryClient): number | false | undefined => {
    const query = queryClient
      .getQueryCache()
      .find({ queryKey: AUGMENT_KEYS.progress(AUG_ID) }) as Query | undefined;
    expect(query).toBeDefined();
    const observer = query?.observers[0];
    const option = observer?.options.refetchInterval;
    return typeof option === 'function'
      ? (option(query as never) as number | false | undefined)
      : option;
  };

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('진행률_조회가_실패하면_폴링을_중단한다', async () => {
    // given — 진행률 API 가 500
    mock.onGet(`/augments/${AUG_ID}/progress`).reply(500, {
      success: false,
      data: null,
      message: 'boom',
      errorCode: 'INTERNAL_ERROR',
    });
    const queryClient = makeClient();

    // when
    const { result } = renderHook(() => useAugmentProgress(AUG_ID), {
      wrapper: wrapperOf(queryClient),
    });
    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });

    // then — 재요청 주기를 잡지 않는다(구 코드는 5000 을 돌려줘 영구 폴링했다)
    expect(evaluateInterval(queryClient)).toBe(false);
  });

  it('정상_응답이면_서버_권고_주기로_폴링한다', async () => {
    // given
    mock.onGet(`/augments/${AUG_ID}/progress`).reply(200, {
      success: true,
      data: {
        id: AUG_ID,
        augTypeCd: 'WINTER',
        status: 'RUNNING',
        progress: 40,
        unavailableReason: null,
        totalJobCount: 2,
        terminalJobCount: 0,
        cancelable: true,
        nextPollAfterMs: 3000,
      },
      message: null,
      errorCode: null,
    });
    const queryClient = makeClient();

    // when
    const { result } = renderHook(() => useAugmentProgress(AUG_ID), {
      wrapper: wrapperOf(queryClient),
    });
    await waitFor(() => {
      expect(result.current.data).toBeDefined();
    });

    // then
    expect(evaluateInterval(queryClient)).toBe(3000);
  });
});
