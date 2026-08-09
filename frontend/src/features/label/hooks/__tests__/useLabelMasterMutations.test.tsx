// Phase 2 — 라벨 마스터 mutation 훅: 성공 시 마스터 목록(LABEL_MASTER_KEYS.all) invalidate 검증.

import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';

import { LABEL_MASTER_KEYS } from '../useLabelMasters';
import {
  useCreateLabelMaster,
  useDeleteLabelMaster,
  useUpdateLabelMaster,
} from '../useLabelMasterMutations';

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

const CREATED = {
  labelId: 99,
  name: '자전거',
  color: '#22C55E',
  type: 'BBOX',
  sortNo: 5,
  useYn: 'Y',
};

describe('useLabelMasterMutations — 성공 시 마스터 목록 invalidate', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => mock.restore());

  it('생성_성공시_LABEL_MASTER_KEYS_all이_invalidate된다', async () => {
    mock.onPost('/manage/labels').reply(201, {
      success: true,
      data: CREATED,
      message: null,
      errorCode: null,
    });
    const qc = newClient();
    const spy = vi.spyOn(qc, 'invalidateQueries');

    const { result } = renderHook(() => useCreateLabelMaster(), { wrapper: createWrapper(qc) });
    await result.current.mutateAsync({ name: '자전거', color: '#22C55E', type: 'BBOX', sortNo: 5 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(spy).toHaveBeenCalledWith({ queryKey: LABEL_MASTER_KEYS.all });
  });

  it('수정_성공시_LABEL_MASTER_KEYS_all이_invalidate된다', async () => {
    mock.onPut('/manage/labels/7').reply(200, {
      success: true,
      data: { ...CREATED, labelId: 7 },
      message: null,
      errorCode: null,
    });
    const qc = newClient();
    const spy = vi.spyOn(qc, 'invalidateQueries');

    const { result } = renderHook(() => useUpdateLabelMaster(), { wrapper: createWrapper(qc) });
    await result.current.mutateAsync({
      labelId: 7,
      body: { name: '자전거', color: '#22C55E', type: 'BBOX', sortNo: 5 },
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(spy).toHaveBeenCalledWith({ queryKey: LABEL_MASTER_KEYS.all });
  });

  it('삭제_성공시_LABEL_MASTER_KEYS_all이_invalidate된다', async () => {
    mock.onDelete('/manage/labels/7').reply(204);
    const qc = newClient();
    const spy = vi.spyOn(qc, 'invalidateQueries');

    const { result } = renderHook(() => useDeleteLabelMaster(), { wrapper: createWrapper(qc) });
    await result.current.mutateAsync(7);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(spy).toHaveBeenCalledWith({ queryKey: LABEL_MASTER_KEYS.all });
  });
});
