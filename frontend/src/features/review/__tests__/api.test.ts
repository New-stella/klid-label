import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import {
  addIssue,
  approveReview,
  getReviewFrames,
  listIssues,
  listReviews,
  rejectReview,
  startReview,
  submitReview,
} from '../api';

describe('review api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('listReviews_GET_reviews_페이지_파라미터_전달', async () => {
    mock.onGet('/reviews').reply((config) => {
      expect(config.params).toMatchObject({ page: 0, size: 20 });
      return [
        200,
        {
          success: true,
          data: {
            content: [
              {
                id: 10,
                videoId: 1,
                cctvName: 'CCTV-1',
                workerId: 7,
                workerName: '홍길동',
                submittedAt: '2026-05-07T10:00:00Z',
                labelCount: 12,
                status: 'REVIEW_PENDING',
              },
            ],
            totalElements: 1,
            totalPages: 1,
            number: 0,
            size: 20,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await listReviews({ page: 0, size: 20 });
    expect(res.content[0].status).toBe('REVIEW_PENDING');
  });

  it('startReview_POST_reviews_id_start_상태_REVIEWING_전이', async () => {
    mock.onPost('/reviews/10/start').reply(200, {
      success: true,
      data: {
        id: 10,
        videoId: 1,
        cctvName: 'CCTV-1',
        workerId: 7,
        workerName: '홍길동',
        submittedAt: '2026-05-07T10:00:00Z',
        labelCount: 12,
        status: 'REVIEWING',
      },
      message: null,
      errorCode: null,
    });

    const res = await startReview(10);
    expect(res.status).toBe('REVIEWING');
  });

  it('approveReview_POST_reviews_id_approve_상태_COMPLETED_전이', async () => {
    mock.onPost('/reviews/10/approve').reply(200, {
      success: true,
      data: {
        id: 10,
        videoId: 1,
        cctvName: 'CCTV-1',
        workerId: 7,
        workerName: '홍길동',
        submittedAt: '2026-05-07T10:00:00Z',
        labelCount: 12,
        status: 'COMPLETED',
      },
      message: null,
      errorCode: null,
    });

    const res = await approveReview(10);
    expect(res.status).toBe('COMPLETED');
  });

  it('approveReview_바디_미지정이어도_JSON_Content_Type으로_전송된다', async () => {
    // 회귀 가드 — 바디를 null 로 보내면 axios 가 POST 기본값인 x-www-form-urlencoded 를 붙이고
    // BE 의 @RequestBody(required=false) 가 415(→500)로 거부해 승인 전 구간이 막혔다.
    let contentType: unknown;
    let rawBody: unknown;
    mock.onPost('/reviews/10/approve').reply((config) => {
      contentType = config.headers?.['Content-Type'] ?? config.headers?.['content-type'];
      rawBody = config.data;
      return [200, { success: true, data: { id: 10, status: 'COMPLETED' }, message: null, errorCode: null }];
    });

    await approveReview(10);

    expect(String(contentType)).toContain('application/json');
    expect(JSON.parse(String(rawBody))).toEqual({});
  });

  it('rejectReview_POST_reviews_id_reject_사유_body_전달_상태_REJECTED_전이', async () => {
    let body: unknown;
    mock.onPost('/reviews/10/reject').reply((config) => {
      body = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          success: true,
          data: {
            id: 10,
            videoId: 1,
            cctvName: 'CCTV-1',
            workerId: 7,
            workerName: '홍길동',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 12,
            status: 'REJECTED',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await rejectReview(10, { reason: '라벨 누락' });
    expect(body).toMatchObject({ reason: '라벨 누락' });
    expect(res.status).toBe('REJECTED');
  });

  it('submitReview_POST_reviews_videoId_submit_검수_제출', async () => {
    // BE 계약: POST /v1/reviews/{videoId}/submit (ReviewController @RequestMapping("/v1") + @PostMapping("/reviews/{videoId}/submit")).
    mock.onPost('/reviews/1/submit').reply(200, {
      success: true,
      data: {
        id: 10,
        videoId: 1,
        cctvName: 'CCTV-1',
        workerId: 7,
        workerName: '홍길동',
        submittedAt: '2026-05-07T11:00:00Z',
        labelCount: 12,
        status: 'REVIEW_PENDING',
      },
      message: null,
      errorCode: null,
    });

    const res = await submitReview(1);
    expect(res.status).toBe('REVIEW_PENDING');
  });

  it('listIssues_GET_reviews_id_issues', async () => {
    mock.onGet('/reviews/10/issues').reply(200, {
      success: true,
      data: [
        { id: 1, frameId: 5, description: '라벨 누락', createdAt: '2026-05-07T10:00:00Z' },
        { id: 2, frameId: 7, description: '클래스 오인식', createdAt: '2026-05-07T10:01:00Z' },
      ],
      message: null,
      errorCode: null,
    });

    const res = await listIssues(10);
    expect(res).toHaveLength(2);
    expect(res[0].frameId).toBe(5);
  });

  it('addIssue_POST_reviews_id_issues_프레임별_누적', async () => {
    let body: unknown;
    mock.onPost('/reviews/10/issues').reply((config) => {
      body = JSON.parse(config.data ?? '{}');
      return [
        201,
        {
          success: true,
          data: { id: 3, frameId: 9, description: '바운딩 박스 어긋남', createdAt: '2026-05-07T10:02:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await addIssue(10, { frameId: 9, description: '바운딩 박스 어긋남' });
    expect(body).toMatchObject({ frameId: 9, description: '바운딩 박스 어긋남' });
    expect(res.id).toBe(3);
  });

  it('getReviewFrames_GET_reviews_videoId_frames_프레임_목록_조회', async () => {
    mock.onGet('/reviews/1/frames').reply(200, {
      success: true,
      data: {
        videoId: 1,
        totalFrames: 25,
        frames: [
          {
            srcSn: 100,
            frameNo: 1,
            imageUrl: '/api/v1/videos/1/frames/1/image',
            labels: [
              {
                id: 11,
                lblTypeCd: 'BBOX',
                label: 'person',
                points: [
                  [10, 20],
                  [30, 40],
                ],
                autoLblYn: 'Y',
                confScore: 0.92,
              },
            ],
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    const res = await getReviewFrames(1);
    expect(res.totalFrames).toBe(25);
    expect(res.frames[0].labels[0].lblTypeCd).toBe('BBOX');
  });
});
