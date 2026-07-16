// R16 — 포털 라벨 저장 훅 페이로드 단언.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { createTestQueryClient } from '@/test/renderWithProviders';
import { useSavePortalLabels } from '@/features/portal/hooks/useSavePortalLabels';
import type { Label } from '@/features/label/types';

describe('useSavePortalLabels', () => {
  let mock: MockAdapter;
  const qc = createTestQueryClient();

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );

  it('저장시_POST_portal_user_labels_페이로드_단언', async () => {
    mock.onPost('/portal/user-labels').reply(201, {
      success: true,
      data: {
        userLblSn: 1,
        sourceRawSn: 7,
        sourceSrcSn: 777,
        lblTypeCd: 'BBOX',
        label: 'person',
        points: '[[1,2],[3,4]]',
        createdAt: '2026-06-06T00:00:00',
      },
      message: null,
      errorCode: null,
    });

    const { result } = renderHook(() => useSavePortalLabels(777, 7), { wrapper });

    const label: Label = {
      id: 'tmp-1',
      frameNo: 0,
      classId: 0,
      className: 'person',
      source: 'MANUAL',
      shape: { type: 'BBOX', left: 1, top: 2, right: 3, bottom: 4 },
    };

    await result.current.mutateAsync([label]);

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    const body = JSON.parse(mock.history.post[0].data);
    expect(body).toMatchObject({
      sourceRawSn: 7,
      sourceSrcSn: 777,
      lblTypeCd: 'BBOX',
      label: 'person',
      points: '[[1,2],[3,4]]',
    });
  });

  it('useSavePortalLabels_POLYGON_직렬화_회귀없음', async () => {
    mock.onPost('/portal/user-labels').reply(201, {
      success: true,
      data: {
        userLblSn: 3,
        sourceRawSn: 7,
        sourceSrcSn: 777,
        lblTypeCd: 'POLYGON',
        label: 'road',
        points: '[[1,2],[3,4],[5,6]]',
        createdAt: '2026-06-06T00:00:00',
      },
      message: null,
      errorCode: null,
    });

    const { result } = renderHook(() => useSavePortalLabels(777, 7), { wrapper });

    const label: Label = {
      id: 'poly-1',
      frameNo: 0,
      classId: 1,
      className: 'road',
      source: 'MANUAL',
      shape: { type: 'POLYGON', points: [1, 2, 3, 4, 5, 6] },
    };

    await result.current.mutateAsync([label]);

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    const body = JSON.parse(mock.history.post[0].data);
    expect(body.lblTypeCd).toBe('POLYGON');
    expect(JSON.parse(body.points)).toEqual([
      [1, 2],
      [3, 4],
      [5, 6],
    ]);
  });

  it('rawSn_없으면_저장_거부', async () => {
    const { result } = renderHook(() => useSavePortalLabels(777, undefined), { wrapper });
    await expect(
      result.current.mutateAsync([
        {
          id: 'tmp-1',
          frameNo: 0,
          classId: 0,
          className: 'person',
          source: 'MANUAL',
          shape: { type: 'BBOX', left: 1, top: 2, right: 3, bottom: 4 },
        },
      ]),
    ).rejects.toThrow();
    expect(mock.history.post).toHaveLength(0);
  });
});
