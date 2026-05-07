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

  it('requestAutolabel_srcSn으로_오토라벨링_요청', async () => {
    let capturedUrl = '';
    mock.onPost(/.*/).reply((config) => {
      capturedUrl = config.url ?? '';
      return [
        200,
        {
          success: true,
          data: { srcSn: 42, detectedCount: 5, elapsedMs: 1200 },
          message: null,
          errorCode: null,
        },
      ];
    });

    const result = await requestAutolabel(42);
    expect(capturedUrl).toBe('/portal/autolabel');
    expect(result.detectedCount).toBe(5);
  });

  it('savePortalLabels_라벨_저장_PUT', async () => {
    let capturedData: unknown = null;
    mock.onPut('/portal/labels/77').reply((config) => {
      capturedData = JSON.parse(config.data);
      return [
        200,
        {
          success: true,
          data: { savedCount: 2 },
          message: null,
          errorCode: null,
        },
      ];
    });

    await savePortalLabels(77, [{ classId: 1, x: 0, y: 0, w: 10, h: 10 }]);
    expect((capturedData as { labels?: unknown[] }).labels).toHaveLength(1);
  });
});
