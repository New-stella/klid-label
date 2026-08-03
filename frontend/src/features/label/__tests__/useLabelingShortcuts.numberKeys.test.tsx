// 2026-08-03 사용자 확정 — 1~9 라벨 선택은 **라벨 선택 모달 전용**으로 이전됐다.
//
// 구 동작(전역 1~9 → activeLabelId 변경)은 좌측 상시 라벨 패널이 있을 때만 성립했다.
// 패널 폐지 후에는 아무 시각 피드백 없이 다음 도형의 라벨이 바뀌는 조용한 상태 변경이라
// 전역 키맵에서 제거하고 모달(LabelPickerModal) 안으로 옮겼다.
//   - 모달 내 동작 검증: `components/__tests__/LabelPickerModal.test.tsx`
//   - 여기서는 **전역에서 더 이상 발화하지 않는다**는 회귀 가드만 둔다.

import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { type ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';

import { SHORTCUT_KEYMAP, useLabelingShortcuts } from '../hooks/useLabelingShortcuts';

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
  window.dispatchEvent(new KeyboardEvent('keydown', { key, ...opts }));
}

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  Wrapper.displayName = 'QueryWrapper';
  return Wrapper;
}

describe('useLabelingShortcuts — 1~9 는 전역 단축키가 아니다(모달 전용)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
  });

  afterEach(() => {
    mock.restore();
  });

  it('전역_키맵에_숫자_바인딩이_없다', () => {
    const digits = SHORTCUT_KEYMAP.filter((b) => /^[1-9]$/.test(b.key));
    expect(digits).toEqual([]);
  });

  it('전역_1_키는_activeLabelId를_바꾸지_않는다', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    await new Promise((r) => setTimeout(r, 50));

    act(() => press('1'));
    expect(useLabelStore.getState().activeLabelId).toBeNull();
  });
});
