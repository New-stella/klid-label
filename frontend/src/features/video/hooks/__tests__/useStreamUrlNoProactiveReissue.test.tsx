/**
 * 재생 주소 발급 훅 — 스스로 다시 받지 않는다. [@design API-114] [@design SCREEN-006]
 *
 * <h3>왜 이 가드가 필요한가</h3>
 * 주소를 다시 받으면 재생 요소의 `src` 가 바뀌어 재생이 멈추고 위치가 흔들린다. 재발급은 재생이
 * <b>실제로 실패했을 때만</b>(화면의 `refetch`) 일어나야 하고, 응답의 만료 시각·유효 초는 재발급
 * 판단에 쓰지 않는다. 구 동작은 서명 수명(60초)의 80%(48초)를 `staleTime` 으로 두어, 그 뒤
 * 네트워크 재연결이 일어나면 조용히 새 주소를 받았다.
 *
 * <h3>왜 전용 QueryClient 를 쓰는가</h3>
 * 공용 시험 클라이언트는 `staleTime: 0` 이고, 운영 클라이언트(`lib/queryClient`)의 기본값과 다르다.
 * 이 가드는 <b>훅이 스스로 거는 옵션</b>을 봐야 하므로 운영과 같은 기본값을 가진 클라이언트를 쓴다
 * (운영 기본값은 창 복귀 재조회가 이미 꺼져 있어, 실제로 새던 길은 재연결이다).
 */
import { type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { QueryClient, QueryClientProvider, focusManager, onlineManager } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';

import { useStreamUrl } from '../useStreamUrl';

function createProductionLikeClient() {
  // lib/queryClient 의 기본값을 그대로 옮긴다(재시도만 끈다 — 시험 시간 단축).
  return new QueryClient({
    defaultOptions: {
      queries: { staleTime: 30_000, retry: false, refetchOnWindowFocus: false },
    },
  });
}

function countRequests(mock: MockAdapter): number {
  return mock.history.get.filter((r) => (r.url ?? '').includes('/videos/7/stream-url')).length;
}

describe('useStreamUrl — 선제 재발급 없음', () => {
  let mock: MockAdapter;
  let client: QueryClient;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet(/\/videos\/7\/stream-url/).reply(200, {
      url: '/videos/7/stream',
      expiresAt: 1,
      ttlSeconds: 60,
    });
    client = createProductionLikeClient();
  });

  afterEach(() => {
    mock.restore();
    client.clear();
    onlineManager.setOnline(true);
    focusManager.setFocused(undefined);
    vi.restoreAllMocks();
  });

  function wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }

  it('★서명_수명이_지난_뒤_재연결·창복귀가_와도_주소를_다시_받지_않는다', async () => {
    const { result } = renderHook(() => useStreamUrl(7), { wrapper });
    await waitFor(() => expect(result.current.data?.url).toBe('/videos/7/stream'));
    expect(countRequests(mock)).toBe(1);

    // given: 구 staleTime(48초)·서명 수명(60초)을 모두 넘긴 시점
    const base = Date.now();
    vi.spyOn(Date, 'now').mockReturnValue(base + 10 * 60 * 1000);

    // when: 네트워크가 끊겼다 돌아오고, 창도 다시 포커스를 얻는다
    act(() => {
      onlineManager.setOnline(false);
    });
    act(() => {
      onlineManager.setOnline(true);
    });
    act(() => {
      focusManager.setFocused(false);
    });
    act(() => {
      focusManager.setFocused(true);
    });
    // 「일어나지 않는다」는 정착 뒤에 단언한다.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });

    // then: 요청이 늘지 않는다 — 재발급은 재생 실패 때 화면이 부르는 refetch 뿐이다.
    expect(countRequests(mock)).toBe(1);
  });

  it('화면이_refetch_를_부르면_그때는_다시_받는다_재생_실패_경로', async () => {
    const { result } = renderHook(() => useStreamUrl(7), { wrapper });
    await waitFor(() => expect(countRequests(mock)).toBe(1));

    await act(async () => {
      await result.current.refetch();
    });

    expect(countRequests(mock)).toBe(2);
  });
});
