// Phase 4 — event_annotation(외부 VLM VQA/CoT) API 클라이언트 테스트.
//
// BE 계약(확정):
//   GET /v1/videos/{rawSn}/event-annotation → ApiResponse<EventAnnotationInfo>
//   PUT /v1/videos/{rawSn}/event-annotation  body EventAnnotationPayload → ApiResponse<EventAnnotationInfo>
// payload 는 caption/evidence 가 c1..cn 키 객체(snake_case 와이어 포맷).

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getEventAnnotation, putEventAnnotation } from '../eventAnnotation';
import type { EventAnnotationPayload } from '../eventAnnotation';

describe('eventAnnotation api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('GET_event_annotation_c1cn_구조_언랩', async () => {
    // given — BE 응답: caption/evidence 는 c1..cn 키 객체
    mock.onGet('/videos/7/event-annotation').reply(200, {
      success: true,
      data: {
        rawSn: 7,
        evntAnnoSn: 11,
        reviewStatus: 'AUTO_GENERATED',
        regId: 'sys',
        mdfcnId: null,
        payload: {
          event_class: '화재',
          question: '무슨 일이 일어나는가?',
          answer: '화재가 발생했다',
          caption: { c1: { caption_text: '연기가 보인다', cot: ['1단계', '2단계', '3단계'] } },
          evidence: {
            c1: {
              evidence_text: '불꽃',
              frame_id: [100, 101],
              obj_id: ['obj-1'],
              obj_bbox: [[10, 20, 30, 40]],
              obj_label: ['fire'],
            },
          },
        },
      },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getEventAnnotation(7);

    // then
    expect(r.rawSn).toBe(7);
    expect(r.reviewStatus).toBe('AUTO_GENERATED');
    expect(r.payload.event_class).toBe('화재');
    expect(r.payload.caption?.c1.cot).toEqual(['1단계', '2단계', '3단계']);
    expect(r.payload.evidence?.c1.frame_id).toEqual([100, 101]);
    expect(r.payload.evidence?.c1.obj_id).toEqual(['obj-1']);
  });

  it('PUT_event_annotation_payload_그대로_전송하고_응답_언랩', async () => {
    // given
    const payload: EventAnnotationPayload = {
      event_class: '침입',
      question: '누가 들어왔는가?',
      answer: '사람 1명',
      caption: { c1: { caption_text: '담을 넘는다', cot: ['접근', '월담', '진입'] } },
      evidence: { c1: { evidence_text: '월담 지점', obj_id: ['person-1'] } },
    };
    mock.onPut('/videos/9/event-annotation').reply((config) => {
      const body = JSON.parse(config.data);
      expect(body).toEqual(payload);
      return [
        200,
        {
          success: true,
          data: {
            rawSn: 9,
            evntAnnoSn: 21,
            reviewStatus: 'PENDING',
            regId: 'w1',
            mdfcnId: 'w1',
            payload,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    const r = await putEventAnnotation(9, payload);

    // then
    expect(r.reviewStatus).toBe('PENDING');
    expect(r.payload.evidence?.c1.obj_id).toEqual(['person-1']);
  });
});
