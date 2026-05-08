import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getBatchStatus, getVideo, listVideos } from '../api';

describe('video api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('listVideos_쿼리_파라미터가_axios_params로_전달', async () => {
    mock.onGet('/videos').reply((config) => {
      expect(config.params).toMatchObject({
        page: 0,
        size: 20,
        cctvNameKeyword: '강남',
        eventTypeCd: 'FALL',
      });
      return [
        200,
        {
          success: true,
          data: {
            content: [],
            totalElements: 0,
            totalPages: 0,
            number: 0,
            size: 20,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const result = await listVideos({
      page: 0,
      size: 20,
      cctvNameKeyword: '강남',
      eventTypeCd: 'FALL',
    });
    expect(result.totalElements).toBe(0);
  });

  it('getVideo_상세_응답_정상_파싱', async () => {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        cctvName: '강남대로 CCTV',
        vmsClipId: 'VMS-42',
        eventName: '낙상',
        eventTypeCd: 'FALL',
        localGov: '강남구',
        frameCount: 900,
        status: 'COMPLETED',
        capturedAt: '2026-05-01T12:00:00Z',
        duration: 30,
        fileSizeMb: 10,
        resolution: '1920x1080',
        framePreviews: [
          { frameNo: 1, thumbnailUrl: '/t/1.jpg' },
          { frameNo: 150, thumbnailUrl: '/t/150.jpg' },
          { frameNo: 300, thumbnailUrl: '/t/300.jpg' },
          { frameNo: 450, thumbnailUrl: '/t/450.jpg' },
          { frameNo: 600, thumbnailUrl: '/t/600.jpg' },
          { frameNo: 750, thumbnailUrl: '/t/750.jpg' },
        ],
      },
      message: null,
      errorCode: null,
    });

    const detail = await getVideo(42);
    expect(detail.id).toBe(42);
    expect(detail.framePreviews).toHaveLength(6);
    expect(detail.cctvName).toBe('강남대로 CCTV');
  });

  it('getBatchStatus_배치_현황_정상_파싱', async () => {
    mock.onGet('/batch/status').reply(200, {
      success: true,
      data: {
        items: [
          {
            rawSn: 1001,
            stage: 'YOLO',
            startedAt: '2026-05-07T10:00:00',
            lastUpdatedAt: '2026-05-07T10:05:00',
            retryCount: 0,
            errorMessage: null,
          },
          {
            rawSn: 1002,
            stage: 'COMPLETED',
            startedAt: '2026-05-07T09:00:00',
            lastUpdatedAt: '2026-05-07T09:30:00',
            retryCount: 0,
            errorMessage: null,
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    const status = await getBatchStatus();
    expect(status.items).toHaveLength(2);
    expect(status.items[0].rawSn).toBe(1001);
    expect(status.items[0].stage).toBe('YOLO');
  });
});
