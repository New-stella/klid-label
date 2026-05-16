// Phase 7 — 단축키 1~9 (라벨 선택) 테스트.
//
// useLabelingShortcuts 가 labelMasters 를 받아 1~9 키 입력 시 해당 인덱스 라벨을 활성화.

import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { type ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelingShortcuts } from '../hooks/useLabelingShortcuts';

const samplePayload = {
  success: true,
  data: [
    { labelId: 11, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y' },
    { labelId: 22, name: '차량', color: '#3B82F6', type: 'BBOX', sortNo: 2, useYn: 'Y' },
    { labelId: 33, name: '자전거', color: '#10B981', type: 'BBOX', sortNo: 3, useYn: 'Y' },
  ],
  message: null,
  errorCode: null,
};

function press(key: string, opts: KeyboardEventInit = {}) {
  const ev = new KeyboardEvent('keydown', { key, ...opts });
  window.dispatchEvent(ev);
}

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

describe('useLabelingShortcuts — 라벨 단축키 1~9', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
  });

  afterEach(() => {
    mock.restore();
  });

  it('단축키_1_누르면_첫번째_라벨_활성화', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);
    const wrapper = makeWrapper();

    // useLabelingShortcuts 자체는 labelMasters 를 내부에서 조회.
    renderHook(() => useLabelingShortcuts(), { wrapper });

    // 라벨 master query 가 끝날 때까지 잠시 대기 (axios mock 즉시 응답이라 microtask 충분)
    await new Promise((r) => setTimeout(r, 50));

    act(() => press('1'));
    expect(useLabelStore.getState().activeLabelId).toBe(11);
  });

  it('단축키_2_누르면_두번째_라벨_활성화', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);
    const wrapper = makeWrapper();
    renderHook(() => useLabelingShortcuts(), { wrapper });
    await new Promise((r) => setTimeout(r, 50));

    act(() => press('2'));
    expect(useLabelStore.getState().activeLabelId).toBe(22);
  });

  it('단축키_9_라벨_범위_초과시_무시', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);
    const wrapper = makeWrapper();
    renderHook(() => useLabelingShortcuts(), { wrapper });
    await new Promise((r) => setTimeout(r, 50));

    act(() => press('9'));
    // 라벨이 3개뿐이라 9 는 범위 밖 → activeLabelId 변경 없음
    expect(useLabelStore.getState().activeLabelId).toBeNull();
  });

  it('INPUT_포커스_시_1_단축키_무시', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);
    const wrapper = makeWrapper();
    renderHook(() => useLabelingShortcuts(), { wrapper });
    await new Promise((r) => setTimeout(r, 50));

    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();

    const ev = new KeyboardEvent('keydown', { key: '1', bubbles: true });
    Object.defineProperty(ev, 'target', { value: input });
    act(() => {
      window.dispatchEvent(ev);
    });

    expect(useLabelStore.getState().activeLabelId).toBeNull();
    document.body.removeChild(input);
  });
});
