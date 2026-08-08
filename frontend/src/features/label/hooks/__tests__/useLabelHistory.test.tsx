// useLabelHistory — 라벨 변경 이력 조회 훅 (Phase 5 / A-2).

import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';
import { LABEL_KEYS } from '@/lib/queryKeys';

import { useLabelHistory } from '../useLabelHistory';

function wrapper(qc: QueryClient) {
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  Wrapper.displayName = 'TestQueryClientWrapper';
  return Wrapper;
}

function newClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  });
}

const payload = {
  success: true,
  data: {
    content: [
      {
        lblHstrySn: 1,
        srcSn: 7,
        regDt: '2026-07-20T10:00:00',
        actor: 'w1',
        addCnt: 1,
        mdfcnCnt: 0,
        delCnt: 0,
        changes: [
          {
            lblSn: 10,
            changeKind: 'ADDED',
            labelName: 'person',
            before: null,
            after: { lblTypeCd: 'BBOX', labelId: 1, labelNm: 'person', pointCn: '[[0,0],[1,1]]' },
          },
        ],
      },
    ],
    number: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
  },
  message: null,
  errorCode: null,
};

describe('useLabelHistory', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  it('LABEL_KEYS_history_키로_page_size를_전달해_조회한다', async () => {
    mock
      .onGet('/frames/7/label-history', { params: { page: 2, size: 20 } })
      .reply(200, payload);

    const qc = newClient();
    const { result } = renderHook(() => useLabelHistory(7, 2, 20), {
      wrapper: wrapper(qc),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.content[0].changes[0].labelName).toBe('person');

    // 캐시가 history 키로 격리되는지 확인
    const cached = qc.getQueryData(LABEL_KEYS.history(7, 2));
    expect(cached).toBeDefined();
  });

  it('srcSn_미정의면_비활성(enabled=false)', async () => {
    const qc = newClient();
    const { result } = renderHook(() => useLabelHistory(undefined), {
      wrapper: wrapper(qc),
    });
    expect(result.current.fetchStatus).toBe('idle');
  });
});
