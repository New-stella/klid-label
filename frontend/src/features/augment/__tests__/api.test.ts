import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import {
  acceptAugment,
  getAugmentResult,
  listAugmentJobs,
  rejectAugment,
  requestAugment,
} from '../api';

describe('augment api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('requestAugment_POST_augments_요청_바디_videoIds_types_전달', async () => {
    let body: unknown;
    mock.onPost('/augments').reply((config) => {
      body = JSON.parse(config.data ?? '{}');
      return [
        201,
        {
          success: true,
          data: { jobId: 100 },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await requestAugment({
      videoIds: [1, 2, 3],
      types: ['WINTER', 'NIGHT'],
    });
    expect(body).toMatchObject({ videoIds: [1, 2, 3], types: ['WINTER', 'NIGHT'] });
    expect(res.jobId).toBe(100);
  });

  it('listAugmentJobs_GET_augments_파라미터_srcSn_전달', async () => {
    mock.onGet('/augments').reply((config) => {
      expect(config.params).toMatchObject({ srcSn: 7 });
      return [
        200,
        {
          success: true,
          data: {
            content: [
              {
                jobId: 1,
                videoId: 10,
                cctvName: 'CCTV-A',
                types: ['WINTER'],
                status: 'COMPLETED',
                requestedAt: '2026-05-07T10:00:00Z',
                videoCount: 1,
              },
            ],
            totalElements: 1,
            totalPages: 1,
            number: 0,
            size: 6,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await listAugmentJobs({ srcSn: 7 });
    expect(res.content).toHaveLength(1);
    expect(res.content[0].status).toBe('COMPLETED');
  });

  it('getAugmentResult_GET_augments_id_result', async () => {
    mock.onGet('/augments/100/result').reply(200, {
      success: true,
      data: {
        jobId: 100,
        results: [
          {
            id: 1,
            videoId: 10,
            cctvName: 'CCTV-A',
            type: 'WINTER',
            framePairs: [
              { srcSn: 1, frameNo: 0, originalUrl: '/o/1.jpg', augmentedUrl: '/a/1.jpg' },
            ],
            decision: 'PENDING',
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    const res = await getAugmentResult(100);
    expect(res.results).toHaveLength(1);
    expect(res.results[0].decision).toBe('PENDING');
  });

  it('acceptAugment_POST_augments_id_accept_상태_ACCEPTED', async () => {
    mock.onPost('/augments/1/accept').reply(200, {
      success: true,
      data: {
        id: 1,
        videoId: 10,
        cctvName: 'CCTV-A',
        type: 'WINTER',
        framePairs: [],
        decision: 'ACCEPTED',
        decidedAt: '2026-05-07T11:00:00Z',
      },
      message: null,
      errorCode: null,
    });

    const res = await acceptAugment(1);
    expect(res.decision).toBe('ACCEPTED');
  });

  it('rejectAugment_POST_augments_id_reject_사유_body_전달', async () => {
    let body: unknown;
    mock.onPost('/augments/1/reject').reply((config) => {
      body = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          success: true,
          data: {
            id: 1,
            videoId: 10,
            cctvName: 'CCTV-A',
            type: 'WINTER',
            framePairs: [],
            decision: 'REJECTED',
            decidedAt: '2026-05-07T11:00:00Z',
            rejectReason: '품질 미달',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await rejectAugment(1, '품질 미달');
    expect(body).toMatchObject({ reason: '품질 미달' });
    expect(res.decision).toBe('REJECTED');
    expect(res.rejectReason).toBe('품질 미달');
  });
});
