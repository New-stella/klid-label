// CO-021 R3 — 포털 라벨의 마스터 연결(labelId)·트랙 연결(trackId) <왕복 보존> 회귀 가드.
//
// 이 두 값은 포털 사용자에게 새 편집 수단을 주는 값이 <아니다>. 데이터마트 원본에서 불러온
// 라벨이 갖고 있던 연결이 저장 왕복에서 끊기지 않도록 화면이 받은 값을 그대로 되돌려 보내는
// 값이며, 산출 어노테이션의 분류·트랙 식별자 조달처다.
//
// ★ 왕복은 두 다리이고 <둘 다> 필요하다 — 어느 한쪽만 보면 통과하면서도 값은 유실된다.
//   ① 로더가 응답의 두 값을 화면 라벨로 옮기는가 (안 옮기면 되돌려 보낼 값 자체가 없다)
//   ② 저장 직렬화가 그 값을 요청에 싣는가
//
// ⚠ 이 결함은 저장 <전>에는 드러나지 않는다 — 화면은 그대로 그려지고 저장도 성공한다.
//   깨진 사실은 재조회·산출 시점에야 보이므로, 이 가드가 유일한 조기 검출 수단이다.
//
// @design API-082, API-110, SCREEN-029
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { createTestQueryClient } from '@/test/renderWithProviders';
import { getPortalLabels } from '@/features/portal/api';
import { useSavePortalLabels } from '@/features/portal/hooks/useSavePortalLabels';
import type { Label } from '@/features/label/types';

describe('포털 라벨 마스터·트랙 연결 왕복 보존 (CO-021 R3)', () => {
  let mock: MockAdapter;
  const qc = createTestQueryClient();

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );

  function replySaveOk() {
    mock.onPost('/portal/user-labels').reply(201, {
      success: true,
      data: {
        userLblSn: 1,
        sourceRawSn: 7,
        sourceSrcSn: 777,
        lblTypeCd: 'BBOX',
        label: 'person',
        points: '[[1,2],[3,4]]',
        createdAt: '2026-08-26T00:00:00',
      },
      message: null,
      errorCode: null,
    });
  }

  // ① 로더 다리 — 응답의 두 값이 화면 라벨까지 실려 온다.
  it('로더가_응답의_labelId_trackId_를_화면_라벨로_옮긴다', async () => {
    mock.onGet('/portal/frames/777/labels').reply(200, {
      success: true,
      data: {
        frameNo: 0,
        srcSn: 777,
        videoId: 7,
        siblings: [{ srcSn: 777, frameNo: 0 }],
        labels: [
          {
            id: 3,
            lblTypeCd: 'BBOX',
            label: 'person',
            points: [
              [1, 2],
              [3, 4],
            ],
            labelId: 42,
            trackId: '7',
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    const res = await getPortalLabels(777);

    expect(res.labels).toHaveLength(1);
    expect(res.labels[0].labelId).toBe(42);
    expect(res.labels[0].trackId).toBe('7');
  });

  // ② 저장 다리 — 화면 라벨의 두 값이 요청에 실린다.
  it('저장_요청에_labelId_trackId_가_실린다', async () => {
    replySaveOk();
    const { result } = renderHook(() => useSavePortalLabels(777, 7), { wrapper });

    const label: Label = {
      id: '3',
      serverId: 3,
      frameNo: 0,
      classId: 42,
      labelId: 42,
      className: 'person',
      source: 'MANUAL',
      trackId: '7',
      shape: { type: 'BBOX', left: 1, top: 2, right: 3, bottom: 4 },
    };

    await result.current.mutateAsync([label]);

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    const body = JSON.parse(mock.history.post[0].data);
    expect(body.labelId).toBe(42);
    expect(body.trackId).toBe('7');
  });

  // 두 다리 연결 — 로더가 준 값이 그대로 저장 요청에 실린다(중간에서 새지 않는다).
  it('로더가_준_값이_그대로_저장_요청에_실린다', async () => {
    mock.onGet('/portal/frames/777/labels').reply(200, {
      success: true,
      data: {
        frameNo: 0,
        srcSn: 777,
        videoId: 7,
        siblings: [],
        labels: [
          {
            id: 9,
            lblTypeCd: 'POLYGON',
            label: 'road',
            points: [
              [1, 2],
              [3, 4],
              [5, 6],
            ],
            labelId: 11,
            trackId: 'trk-9',
          },
        ],
      },
      message: null,
      errorCode: null,
    });
    replySaveOk();

    const loaded = await getPortalLabels(777);
    const { result } = renderHook(() => useSavePortalLabels(777, 7), { wrapper });
    await result.current.mutateAsync(loaded.labels);

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    const body = JSON.parse(mock.history.post[0].data);
    expect(body.labelId).toBe(11);
    expect(body.trackId).toBe('trk-9');
  });

  // 두 값이 없는 라벨(신규 작성분·컬럼 신설 이전 저장분)도 저장이 깨지지 않는다.
  // 없는 값을 지어내지 않고 null 로 보낸다 — 라벨명으로 마스터 PK 를 역추정하면 동명 마스터에
  // 잘못 이어 붙어 <다른 분류로> 저장된다.
  it('두_값이_없으면_지어내지_않고_null_로_보낸다', async () => {
    replySaveOk();
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
    expect(body.labelId).toBeNull();
    expect(body.trackId).toBeNull();
    // 기존 필드는 그대로 — 이 변경은 추가만이다.
    expect(body).toMatchObject({
      sourceRawSn: 7,
      sourceSrcSn: 777,
      lblTypeCd: 'BBOX',
      label: 'person',
      points: '[[1,2],[3,4]]',
    });
  });
});
