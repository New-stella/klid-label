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

  // BE 계약(v1.3 + ADR-059): videoIds·types 는 각각 길이 1이고 types 는 단일값 `AUGMENT` 다.
  // `mtdt` 는 최상위 객체이고 `prompt` 는 **자유 지시문 문자열**이다(구 5필드 객체 prompt 는 400).
  // ⚠ `evntType`·`evntSubtype` 은 요청 본문에서 **사라졌다** — 서버가 중립값을 고정 송신한다.
  it('requestAugment_POST_augments_request_요청_바디_types_mtdt_prompt_전달', async () => {
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
      types: ['AUGMENT'],
      mtdt: {
        time: 'NIGHT',
        season: 'WINTER',
        weather: 'RAIN',
        terrain: 'ROAD',
        severity: 'HIGH',
      },
      prompt: '원본 카메라 시점을 유지해줘.',
    });
    expect(body).toMatchObject({
      videoIds: [1],
      types: ['AUGMENT'],
      mtdt: {
        time: 'NIGHT',
        season: 'WINTER',
        weather: 'RAIN',
        terrain: 'ROAD',
        severity: 'HIGH',
      },
      prompt: '원본 카메라 시점을 유지해줘.',
    });
    // 구 계약(5필드 객체 prompt)으로 되돌아가지 않는다 — prompt 는 문자열이다.
    expect(typeof (body as { prompt: unknown }).prompt).toBe('string');
    // ⚠ BE 는 모르는 필드를 400 이 아니라 조용히 무시한다 — 값 축으로 고정하지 않으면
    //   이벤트 유형이 되살아나도 아무 신호가 오지 않는다(ADR-059).
    expect(body).not.toHaveProperty('evntType');
    expect(body).not.toHaveProperty('evntSubtype');
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
