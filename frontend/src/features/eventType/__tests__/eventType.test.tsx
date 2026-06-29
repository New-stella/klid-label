import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { useEventTypeLabels, useEventTypes } from '@/features/eventType/hooks';
import { labelOf } from '@/lib/eventTypeLabel';
import { apiClient } from '@/lib/api/client';
import { createTestQueryClient } from '@/test/renderWithProviders';

const NINE_CATEGORIES = [
  { categoryKey: '010001', label: '침수(범람)', memberCodes: ['EV01000101', 'EV01000102'] },
  { categoryKey: '010002', label: '산사태', memberCodes: ['EV01000201'] },
  { categoryKey: '020001', label: '화재', memberCodes: ['EV02000101'] },
  { categoryKey: '020002', label: '쓰러짐', memberCodes: ['EV02000201'] },
  { categoryKey: '030001', label: '파손', memberCodes: ['EV03000101'] },
  { categoryKey: '040001', label: '교통사고', memberCodes: ['EV04000101'] },
  { categoryKey: '050001', label: '싸움', memberCodes: ['EV05000101'] },
  { categoryKey: '060001', label: '흉기소지', memberCodes: ['EV06000101'] },
  { categoryKey: '070001', label: '납치(유괴)', memberCodes: ['EV07000101'] },
];

const LABEL_MAP: Record<string, string> = {
  EV01000102: '침수(범람)',
  EV02000201: '쓰러짐',
  EV07000201: '기타 상황',
};

function wrapper() {
  const client = createTestQueryClient();
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe('eventType hooks', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/event-types').reply(200, {
      success: true,
      data: NINE_CATEGORIES,
      message: null,
      errorCode: null,
    });
    mock.onGet('/event-types/labels').reply(200, {
      success: true,
      data: LABEL_MAP,
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => mock.restore());

  it('useEventTypes_훅이_API_9카테고리를_반환한다', async () => {
    const { result } = renderHook(() => useEventTypes(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(9);
    expect(result.current.data?.[0]).toMatchObject({
      categoryKey: '010001',
      label: '침수(범람)',
    });
  });

  it('useEventTypeLabels_훅이_코드_라벨_맵을_반환한다', async () => {
    const { result } = renderHook(() => useEventTypeLabels(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.EV01000102).toBe('침수(범람)');
    expect(result.current.data?.EV07000201).toBe('기타 상황');
  });
});

describe('labelOf', () => {
  it('라벨해석_EV01000102가_침수범람으로_표시된다', () => {
    expect(labelOf(LABEL_MAP, 'EV01000102')).toBe('침수(범람)');
  });

  it('비수집_EV07000201도_기타_상황으로_표시된다', () => {
    expect(labelOf(LABEL_MAP, 'EV07000201')).toBe('기타 상황');
  });

  it('미등록_코드는_원문으로_폴백된다', () => {
    expect(labelOf(LABEL_MAP, 'EV99999999')).toBe('EV99999999');
  });

  it('null_undefined_빈값은_dash_반환', () => {
    expect(labelOf(LABEL_MAP, null)).toBe('-');
    expect(labelOf(LABEL_MAP, undefined)).toBe('-');
    expect(labelOf(LABEL_MAP, '')).toBe('-');
  });

  it('맵이_로딩중_undefined_여도_코드_원문_폴백', () => {
    expect(labelOf(undefined, 'EV01000102')).toBe('EV01000102');
  });
});
