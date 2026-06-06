// R17 이슈2 — 포털 라벨 Load 시 points 가 비어있는(stale) 라벨은 안전 스킵.
// 빈 좌표 라벨이 캔버스로 흘러가 렌더 크래시 → navigate(-1) 튕김을 유발하던 회귀를
// normalize 단계에서 차단한다 (LabelsLayer 등 캔버스 레이어는 건드리지 않음).
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { getPortalLabels } from '@/features/portal/api';

describe('getPortalLabels 빈 좌표 라벨 스킵', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('points_빈배열_또는_누락_라벨은_제외하고_유효_라벨만_반환', async () => {
    mock.onGet('/portal/frames/777/labels').reply(200, {
      success: true,
      data: {
        frameNo: 0,
        srcSn: 777,
        videoId: 7,
        siblings: [{ srcSn: 777, frameNo: 0 }],
        labels: [
          { id: 1, lblTypeCd: 'BBOX', label: 'empty', points: [] }, // 빈 좌표 → 제외
          { id: 2, lblTypeCd: 'BBOX', label: 'no-points' }, // points 누락 → 제외
          { id: 3, lblTypeCd: 'BBOX', label: 'person', points: [[1, 2], [3, 4]] }, // 유효
        ],
      },
      message: null,
      errorCode: null,
    });

    const res = await getPortalLabels(777);

    expect(res.labels).toHaveLength(1);
    expect(res.labels[0].className ?? (res.labels[0] as { label?: string }).label).toBeDefined();
    expect(res.labels[0].id).toBe('3');
  });
});
