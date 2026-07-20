// Phase 2 — 라벨 속성 정의 훅: 조회 + mutation 성공 시 해당 라벨 속성(LABEL_ATTR_KEYS.byLabel) invalidate 검증.

import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';

import {
  LABEL_ATTR_KEYS,
  useCreateLabelAttr,
  useDeleteLabelAttr,
  useLabelAttrs,
  useUpdateLabelAttr,
} from '../useLabelAttrs';

function createWrapper(qc: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

function newClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });
}

const ATTR = {
  attrId: 20,
  labelId: 3,
  name: '색상',
  inputType: 'SELECT',
  valuesJson: '["빨강","파랑"]',
  defaultVal: '빨강',
  mutable: 'Y',
  sortNo: 1,
  useYn: 'Y',
};

const UPSERT = {
  name: '색상',
  inputType: 'SELECT' as const,
  valuesJson: '["빨강","파랑"]',
  defaultVal: '빨강',
  mutable: 'Y' as const,
  sortNo: 1,
};

describe('useLabelAttrs — 조회 + 성공 시 라벨별 속성 invalidate', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => mock.restore());

  it('useLabelAttrs가_labelId로_조회한다', async () => {
    mock.onGet('/manage/labels/3/attrs').reply(200, {
      success: true,
      data: [ATTR],
      message: null,
      errorCode: null,
    });
    const qc = newClient();

    const { result } = renderHook(() => useLabelAttrs(3), { wrapper: createWrapper(qc) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0].attrId).toBe(20);
  });

  it('labelId가_0이면_조회하지_않는다', () => {
    const qc = newClient();
    const { result } = renderHook(() => useLabelAttrs(0), { wrapper: createWrapper(qc) });
    expect(result.current.fetchStatus).toBe('idle');
  });

  it('생성_성공시_byLabel_3이_invalidate된다', async () => {
    mock.onPost('/manage/labels/3/attrs').reply(201, {
      success: true,
      data: ATTR,
      message: null,
      errorCode: null,
    });
    const qc = newClient();
    const spy = vi.spyOn(qc, 'invalidateQueries');

    const { result } = renderHook(() => useCreateLabelAttr(3), { wrapper: createWrapper(qc) });
    await result.current.mutateAsync(UPSERT);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(spy).toHaveBeenCalledWith({ queryKey: LABEL_ATTR_KEYS.byLabel(3) });
  });

  it('수정_성공시_byLabel_3이_invalidate된다', async () => {
    mock.onPut('/manage/labels/3/attrs/20').reply(200, {
      success: true,
      data: ATTR,
      message: null,
      errorCode: null,
    });
    const qc = newClient();
    const spy = vi.spyOn(qc, 'invalidateQueries');

    const { result } = renderHook(() => useUpdateLabelAttr(3), { wrapper: createWrapper(qc) });
    await result.current.mutateAsync({ attrId: 20, body: UPSERT });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(spy).toHaveBeenCalledWith({ queryKey: LABEL_ATTR_KEYS.byLabel(3) });
  });

  it('삭제_성공시_byLabel_3이_invalidate된다', async () => {
    mock.onDelete('/manage/labels/3/attrs/20').reply(204);
    const qc = newClient();
    const spy = vi.spyOn(qc, 'invalidateQueries');

    const { result } = renderHook(() => useDeleteLabelAttr(3), { wrapper: createWrapper(qc) });
    await result.current.mutateAsync(20);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(spy).toHaveBeenCalledWith({ queryKey: LABEL_ATTR_KEYS.byLabel(3) });
  });
});
