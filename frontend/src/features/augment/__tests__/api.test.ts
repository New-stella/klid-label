import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import {
  acceptAugment,
  getAugmentResult,
  listAugmentJobs,
  rejectAugment,
  requestAugment,
  restoreAugment,
} from '../api';

describe('augment api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  // BE 계약(2026-07-31): videoIds·types 는 각각 길이 1, prompt 5필드는 **필수**.
  // prompt 를 빠뜨리면 BE 가 400 INVALID_INPUT 으로 거부한다.
  it('requestAugment_POST_augments_request_요청_바디_videoIds_types_prompt_전달', async () => {
    let body: unknown;
    mock.onPost('/augments/request').reply((config) => {
      body = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          success: true,
          data: {
            jobId: 100,
            requestedAt: '2026-05-19T10:00:00Z',
            videoCount: 1,
            typeCount: 1,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await requestAugment({
      videoIds: [1],
      types: ['WINTER'],
      prompt: {
        time: 'NIGHT',
        season: 'WINTER',
        weather: 'RAIN',
        terrain: 'ROAD',
        severity: 'HIGH',
      },
    });
    expect(body).toMatchObject({
      videoIds: [1],
      types: ['WINTER'],
      prompt: {
        time: 'NIGHT',
        season: 'WINTER',
        weather: 'RAIN',
        terrain: 'ROAD',
        severity: 'HIGH',
      },
    });
    expect(res.jobId).toBe(100);
    expect(res.videoCount).toBe(1);
    expect(res.typeCount).toBe(1);
    expect(res.requestedAt).toBe('2026-05-19T10:00:00Z');
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

  it('getAugmentResult_프레임쌍_페이징_파라미터를_전달한다', async () => {
    // given
    mock.onGet('/augments/100/result').reply(200, {
      success: true,
      data: { jobId: 100, status: 'COMPLETED', page: 1, size: 12, results: [] },
      message: null,
      errorCode: null,
    });

    // when
    const res = await getAugmentResult(100, { page: 1, size: 12 });

    // then
    expect(mock.history.get[0].params).toMatchObject({ page: 1, size: 12 });
    expect(res.page).toBe(1);
    expect(res.size).toBe(12);
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

  // 복구 응답 본문은 화면이 쓰지 않는다(무효화 후 재조회가 진실). 계약상 중요한 것은
  // **경로와 바디(사유만)** 다 — 다른 필드를 실어 보내면 Mass Assignment 표면이 된다.
  it('restoreAugment_POST_augments_id_restore_사유_body_전달', async () => {
    let body: unknown;
    mock.onPost('/augments/1/restore').reply((config) => {
      body = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          success: true,
          data: { id: 1, decision: 'PENDING' },
          message: null,
          errorCode: null,
        },
      ];
    });

    await restoreAugment(1, '오판이라 되돌립니다');

    expect(mock.history.post[0].url).toBe('/augments/1/restore');
    expect(body).toEqual({ reason: '오판이라 되돌립니다' });
  });
});
