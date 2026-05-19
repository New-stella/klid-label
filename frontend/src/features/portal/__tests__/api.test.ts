import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { listMyUploads, requestAutolabel, savePortalLabels } from '../api';

describe('portal api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('listMyUploads_본인_업로드_목록_조회', async () => {
    mock.onGet('/portal/uploads').reply(200, {
      success: true,
      data: [
        {
          srcSn: 1,
          displayName: 'sample.mp4',
          uploadedAt: '2026-05-07T10:00:00Z',
          status: 'COMPLETED',
          fileSize: 1024,
        },
      ],
      message: null,
      errorCode: null,
    });

    const result = await listMyUploads();
    expect(result).toHaveLength(1);
    expect(result[0].srcSn).toBe(1);
  });

  it('requestAutolabel_portalVideoSn과_imageB64로_오토라벨링_요청', async () => {
    let capturedUrl = '';
    mock.onPost(/.*/).reply((config) => {
      capturedUrl = config.url ?? '';
      return [
        200,
        {
          success: true,
          data: {
            detections: [
              { label: 'person', points: [10, 20, 30, 40], score: 0.9, trackId: null },
              { label: 'car', points: [50, 60, 70, 80], score: 0.85, trackId: null },
              { label: 'dog', points: [90, 100, 110, 120], score: 0.75, trackId: null },
              { label: 'bike', points: [1, 2, 3, 4], score: 0.7, trackId: null },
              { label: 'tree', points: [5, 6, 7, 8], score: 0.65, trackId: null },
            ],
            mock: false,
            source: 'model',
            mockReason: null,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const result = await requestAutolabel(42, 'data:image/png;base64,AAAA');
    expect(capturedUrl).toBe('/portal/autolabel');
    expect(result.detections).toHaveLength(5);
  });

  it('savePortalLabels_라벨_저장_POST', async () => {
    let capturedData: unknown = null;
    mock.onPost('/portal/labels').reply((config) => {
      capturedData = JSON.parse(config.data);
      return [
        200,
        {
          success: true,
          data: { portalVideoSn: 77, items: [] },
          message: null,
          errorCode: null,
        },
      ];
    });

    await savePortalLabels(77, [
      { label: 'person', lblTypeCd: 'BBOX', points: [[0, 0, 10, 10]] },
    ]);
    expect((capturedData as { items?: unknown[] }).items).toHaveLength(1);
    expect((capturedData as { portalVideoSn?: number }).portalVideoSn).toBe(77);
  });
});
