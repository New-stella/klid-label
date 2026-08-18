// 서버가 준 대기 예산을 화면에 **실제로 흘려보내는** 배선.
//
// 판정 로직(계산·폴백)은 `aiWaitBudget.test.ts` 가 본다. 여기서 보는 것은 «조회 응답이 예산
// 판정기에 도달하는가» 하나다 — 도달하지 않으면 판정기가 아무리 정확해도 화면은 영원히 폴백을
// 쓰고, 서버가 예산을 바꿔도 아무 일도 일어나지 않는다(이 과제가 없애려는 상태 그대로).

import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { AI_WAIT_BUDGET_FALLBACK, aiWaitTimeoutMs, resetAiWaitBudgets } from '@/features/label/aiBudget';
import { useAiWaitBudgetSync } from '@/features/label/hooks/useAiWaitBudgetSync';
import { apiClient } from '@/lib/api/client';

let mock: MockAdapter;
let qc: QueryClient;

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  mock = new MockAdapter(apiClient);
  qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
});

afterEach(() => {
  mock.restore();
  resetAiWaitBudgets();
});

describe('AI 대기 예산 — 서버 값 수신 배선', () => {
  it('조회_응답의_예산이_판정기에_반영된다', async () => {
    // given
    mock.onGet('/ai-defaults').reply(200, {
      success: true,
      data: {
        confThreshold: 25,
        simplifyTolerance: 2,
        waitBudgets: {
          autolabel: { baseSec: 321, perFrameSec: 0, ceilingSec: 900 },
          sam2Track: { baseSec: 5, perFrameSec: 7, ceilingSec: 900 },
        },
      },
      errorCode: null,
    });

    // when
    renderHook(() => useAiWaitBudgetSync(), { wrapper });

    // then
    await waitFor(() => expect(aiWaitTimeoutMs('autolabel')).toBe(321_000));
    expect(aiWaitTimeoutMs('sam2Track', 3)).toBe(26_000);
  });

  it('조회가_실패하면_폴백_예산을_유지한다', async () => {
    // ★ 여기서 짧은 값으로 떨어지면 «정상 동작이 AI 실패로 보이는» 원래 결함이 그대로 돌아온다.
    mock.onGet('/ai-defaults').reply(500);

    const { result } = renderHook(() => useAiWaitBudgetSync(), { wrapper });
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(aiWaitTimeoutMs('autolabel')).toBe(AI_WAIT_BUDGET_FALLBACK.autolabel.baseSec * 1000);
    expect(aiWaitTimeoutMs('sam2Track', 1)).toBeGreaterThan(30_000);
  });

  it('응답에_예산이_없으면_폴백_예산을_유지한다', async () => {
    // 서버가 아직 이 계약을 내려주지 않는 배포 형상 — 화면은 종전대로 동작해야 한다.
    mock.onGet('/ai-defaults').reply(200, {
      success: true,
      data: { confThreshold: 25, simplifyTolerance: 2 },
      errorCode: null,
    });

    const { result } = renderHook(() => useAiWaitBudgetSync(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(aiWaitTimeoutMs('segment')).toBe(AI_WAIT_BUDGET_FALLBACK.segment.baseSec * 1000);
  });
});
