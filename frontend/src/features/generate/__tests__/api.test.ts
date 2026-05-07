import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getBackgroundGenerateJob, requestBackgroundGenerate } from '../api';

describe('generate api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('requestBackgroundGenerate_POST_generate_background_바디_전달', async () => {
    let body: unknown;
    mock.onPost('/generate/background').reply((config) => {
      body = JSON.parse(config.data ?? '{}');
      return [
        201,
        {
          success: true,
          data: { jobId: 555 },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await requestBackgroundGenerate({
      videoId: 10,
      srcSn: 7,
      genType: 'WILDFIRE',
    });
    expect(body).toMatchObject({ videoId: 10, srcSn: 7, genType: 'WILDFIRE' });
    expect(res.jobId).toBe(555);
  });

  it('getBackgroundGenerateJob_GET_generate_background_id', async () => {
    mock.onGet('/generate/background/555').reply(200, {
      success: true,
      data: {
        jobId: 555,
        videoId: 10,
        cctvName: 'CCTV-A',
        srcSn: 7,
        frameNo: 5,
        genType: 'FLOOD',
        requestedAt: '2026-05-07T13:00:00Z',
      },
      message: null,
      errorCode: null,
    });

    const res = await getBackgroundGenerateJob(555);
    expect(res.genType).toBe('FLOOD');
    expect(res.cctvName).toBe('CCTV-A');
  });
});
