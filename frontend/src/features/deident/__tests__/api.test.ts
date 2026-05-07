import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getDeidentDetail, listDeidentResults, reprocessDeident } from '../api';

describe('deident api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('비식별_목록_쿼리_파라미터_axios_params로_전달', async () => {
    mock.onGet('/deident').reply((config) => {
      expect(config.params).toMatchObject({
        page: 0,
        size: 20,
        prvcYn: 'Y',
        status: 'COMPLETED',
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

    const r = await listDeidentResults({
      page: 0,
      size: 20,
      prvcYn: 'Y',
      status: 'COMPLETED',
    });
    expect(r.totalElements).toBe(0);
  });

  it('비식별_상세_조회', async () => {
    mock.onGet('/deident/42').reply(200, {
      success: true,
      data: {
        videoId: 42,
        cctvName: '강남대로 CCTV',
        vmsClipId: 'VMS-42',
        prvcType: 'PRVC',
        prvcYn: 'Y',
        status: 'COMPLETED',
        totalFrames: 900,
        processedFrames: 900,
        failedFrames: 0,
        framePairs: [
          { srcSn: 1, frameNo: 1, originalUrl: '/o/1.jpg', processedUrl: '/d/1.jpg' },
        ],
        history: [
          { attemptNo: 1, attemptedAt: '2026-05-01T10:00:00Z', status: 'COMPLETED' },
        ],
      },
      message: null,
      errorCode: null,
    });

    const r = await getDeidentDetail(42);
    expect(r.videoId).toBe(42);
    expect(r.framePairs).toHaveLength(1);
  });

  it('재처리_POST_deident_id_reprocess_호출', async () => {
    let called = false;
    mock.onPost('/deident/42/reprocess').reply(() => {
      called = true;
      return [
        200,
        { success: true, data: null, message: null, errorCode: null },
      ];
    });
    await reprocessDeident(42);
    expect(called).toBe(true);
  });
});
