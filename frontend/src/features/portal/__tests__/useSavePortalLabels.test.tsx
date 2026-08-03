// R16 — 포털 라벨 저장 훅 페이로드 단언 + 배타 실행(busy) 배선.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { createTestQueryClient } from '@/test/renderWithProviders';
import { useSavePortalLabels } from '@/features/portal/hooks/useSavePortalLabels';
import type { Label } from '@/features/label/types';
import { useLabelStore } from '@/stores/useLabelStore';

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

  it('KEYPOINT_MASK_라벨은_전송하지_않고_BBOX_POLYGON만_저장한다', async () => {
    // 포털 라벨링은 BBOX/POLYGON 만이다(CLAUDE.md 포털 절). 구 "Phase 9 — 포털 키포인트(SKELETON)
    // 허용" 정책은 폐기됐고 BE 가 allowlist 로 400 을 낸다. 데이터마트 원본에서 로드된
    // KEYPOINT/MASK 가 섞여 있어도 저장 전체가 실패하면 안 되므로 FE 가 대상에서 제외한다
    // (형제 useSaveUploadLabels.serializeUploadLabel 과 동일 규칙).
    mock.onPost('/portal/user-labels').reply(201, {
      success: true,
      data: null,
      message: null,
      errorCode: null,
    });

    const { result } = renderHook(() => useSavePortalLabels(777, 7), { wrapper });

    const labels: Label[] = [
      {
        id: 'kp-1',
        frameNo: 0,
        classId: 0,
        className: 'person',
        source: 'MANUAL',
        shape: {
          type: 'KEYPOINT',
          keypoints: Array.from({ length: 17 }, () => ({ x: 1, y: 1, v: 2 })),
        },
      },
      {
        id: 'mask-1',
        frameNo: 0,
        classId: 1,
        className: 'road',
        source: 'MANUAL',
        shape: { type: 'MASK', rle: 'abc', width: 10, height: 10 },
      },
      {
        id: 'bbox-1',
        frameNo: 0,
        classId: 2,
        className: 'car',
        source: 'MANUAL',
        shape: { type: 'BBOX', left: 1, top: 2, right: 3, bottom: 4 },
      },
    ];

    await result.current.mutateAsync(labels);

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    const body = JSON.parse(mock.history.post[0].data);
    expect(body.lblTypeCd).toBe('BBOX');
  });

  it('포털_저장도_같은_배타축(busy_SAVE)을_점유한다', async () => {
    // given: 이 화면에서 가장 긴 작업(라벨 수만큼 순차 POST)인데 락을 안 잡으면
    //   저장 도중 AI 분할/추적이 그대로 시작돼 진행 축이 둘로 갈린다(R7 단일 진실원 위반).
    useLabelStore.getState().reset();
    const releaseRef: { fn: (() => void) | null } = { fn: null };
    mock.onPost('/portal/user-labels').reply(
      () =>
        new Promise((resolve) => {
          releaseRef.fn = () => resolve([201, { success: true, data: null, message: null, errorCode: null }]);
        }),
    );
    const { result } = renderHook(() => useSavePortalLabels(777, 7), { wrapper });

    // when: 저장 in-flight
    const run = result.current.mutateAsync([
      {
        id: 'tmp-1',
        frameNo: 0,
        classId: 0,
        className: 'person',
        source: 'MANUAL',
        shape: { type: 'BBOX', left: 1, top: 2, right: 3, bottom: 4 },
      },
    ]);
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));

    // then: 진행 중 AI 작업 시작은 거부된다.
    expect(useLabelStore.getState().beginBusy('AI_SEGMENT', { srcSn: 777 }).ok).toBe(false);
    // 진행 표시도 store busy 파생 — 별도 축(TanStack isPending)이 아니다.
    expect(result.current.isPending).toBe(true);

    releaseRef.fn?.();
    await run;
    await waitFor(() => expect(useLabelStore.getState().busy).toBeNull());
  });

  it('포털_저장이_취소되면_null을_돌려주고_성공후처리를_하지_않는다', async () => {
    // given
    useLabelStore.getState().reset();
    const onSuccess = vi.fn();
    const releaseRef: { fn: (() => void) | null } = { fn: null };
    mock.onPost('/portal/user-labels').reply(
      () =>
        new Promise((resolve) => {
          releaseRef.fn = () => resolve([201, { success: true, data: null, message: null, errorCode: null }]);
        }),
    );
    const { result } = renderHook(() => useSavePortalLabels(777, 7, { onSuccess }), { wrapper });
    const run = result.current.mutateAsync([
      {
        id: 'tmp-1',
        frameNo: 0,
        classId: 0,
        className: 'person',
        source: 'MANUAL',
        shape: { type: 'BBOX', left: 1, top: 2, right: 3, bottom: 4 },
      },
    ]);
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));

    // when: 취소(프레임 전환 등) 후 응답 도착
    useLabelStore.getState().cancelBusy();
    releaseRef.fn?.();
    const saved = await run;

    // then: 호출측이 `=== null` 로 폐기를 판정할 수 있어야 이동/ dirty 해제를 막는다.
    expect(saved).toBeNull();
    expect(onSuccess).not.toHaveBeenCalled();
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
