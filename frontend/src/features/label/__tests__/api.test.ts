import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import {
  getLabels,
  normalizeLabel,
  putLabels,
  reportDeidentMiss,
  requestAutolabel,
  requestSam2Segment,
} from '../api';
import type { AutolabelResponse, Sam2SegmentResponse } from '../api';
import { saveAndCommit } from '../SaveCommitFlow';
import type { KeypointShape, Label } from '../types';

function bbox(id: string, frameNo: number): Label {
  return {
    id,
    frameNo,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 20, right: 100, bottom: 80 },
  };
}

describe('label api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('getLabels_GET_frames_srcSn_labels', async () => {
    mock.onGet('/frames/777/labels').reply(200, {
      success: true,
      data: { frameNo: 1, srcSn: 777, labels: [] },
      message: null,
      errorCode: null,
    });

    const res = await getLabels(777);
    expect(res.srcSn).toBe(777);
    expect(res.labels).toEqual([]);
  });

  it('getLabels_BE_items_의_trackId_String_은_정규화_시_그대로_노출', async () => {
    mock.onGet('/frames/888/labels').reply(200, {
      success: true,
      data: {
        frameNo: 2,
        srcSn: 888,
        siblings: [],
        items: [
          {
            id: 11,
            lblTypeCd: 'BBOX',
            label: 'person',
            points: [
              [10, 20],
              [100, 80],
            ],
            autoLblYn: 'Y',
            confScore: 0.9,
            trackId: '7',
          },
          {
            id: 12,
            lblTypeCd: 'BBOX',
            label: 'car',
            points: [
              [0, 0],
              [10, 10],
            ],
            autoLblYn: 'Y',
            confScore: 0.8,
            trackId: null,
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    const res = await getLabels(888);
    expect(res.labels).toHaveLength(2);
    expect(res.labels[0].trackId).toBe('7');
    // null → undefined or null 둘 다 허용 — null 이 유지되거나 미설정
    expect(res.labels[1].trackId == null).toBe(true);
  });

  it('api_응답의_lblSrcCd_가_Label_객체로_정상_매핑', async () => {
    mock.onGet('/frames/889/labels').reply(200, {
      success: true,
      data: {
        frameNo: 3,
        srcSn: 889,
        siblings: [],
        items: [
          {
            id: 21,
            lblTypeCd: 'BBOX',
            label: 'person',
            points: [
              [0, 0],
              [10, 10],
            ],
            autoLblYn: 'Y',
            confScore: 0.0,
            trackId: '5',
            lblSrcCd: 'INTERPOLATED',
          },
          {
            id: 22,
            lblTypeCd: 'BBOX',
            label: 'person',
            points: [
              [0, 0],
              [10, 10],
            ],
            autoLblYn: 'Y',
            confScore: 0.9,
            trackId: '5',
            lblSrcCd: null,
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    const res = await getLabels(889);
    expect(res.labels).toHaveLength(2);
    expect(res.labels[0].lblSrcCd).toBe('INTERPOLATED');
    expect(res.labels[1].lblSrcCd == null).toBe(true);
  });

  // Hotfix: BE 응답에 confScore: null 이 들어오면 (수동 라벨 — LS_DATA_LBL_AI_INFO 행 없음)
  // FE 가 Number(null) === 0 으로 강제 변환하여 confidence=0 이 되었고,
  // ObjectAttributePanel 이 "낮은 신뢰도" 배지와 신뢰도 바 0% 를 잘못 표시.
  // null/undefined 모두 undefined 로 정규화하여 표시 자체를 막아야 함.
  describe('confidence 매핑 정규화 (null/undefined → undefined)', () => {
    it('mapLabelToFrontend_BE_confScore_null_이면_confidence_undefined', async () => {
      mock.onGet('/frames/1001/labels').reply(200, {
        success: true,
        data: {
          frameNo: 1,
          srcSn: 1001,
          siblings: [],
          items: [
            {
              id: 41,
              lblTypeCd: 'BBOX',
              label: 'car',
              points: [
                [0, 0],
                [10, 10],
              ],
              autoLblYn: 'N', // 수동
              confScore: null, // BE: AI_INFO 없음 → null
              trackId: null,
            },
          ],
        },
        message: null,
        errorCode: null,
      });

      const res = await getLabels(1001);
      expect(res.labels).toHaveLength(1);
      expect(res.labels[0].source).toBe('MANUAL');
      // 핵심: 0 이 아니라 undefined 여야 함
      expect(res.labels[0].confidence).toBeUndefined();
    });

    it('mapLabelToFrontend_BE_confScore_숫자면_confidence_그_값', async () => {
      mock.onGet('/frames/1002/labels').reply(200, {
        success: true,
        data: {
          frameNo: 1,
          srcSn: 1002,
          siblings: [],
          items: [
            {
              id: 42,
              lblTypeCd: 'BBOX',
              label: 'person',
              points: [
                [0, 0],
                [10, 10],
              ],
              autoLblYn: 'Y',
              confScore: 0.85,
              trackId: null,
            },
          ],
        },
        message: null,
        errorCode: null,
      });

      const res = await getLabels(1002);
      expect(res.labels[0].confidence).toBe(0.85);
    });

    it('mapLabelToFrontend_BE_confScore_누락_confidence_null_이면_confidence_undefined', async () => {
      // confScore 필드 자체 누락 + legacy confidence 도 null 인 경우
      mock.onGet('/frames/1003/labels').reply(200, {
        success: true,
        data: {
          frameNo: 1,
          srcSn: 1003,
          siblings: [],
          items: [
            {
              id: 43,
              lblTypeCd: 'BBOX',
              label: 'car',
              points: [
                [0, 0],
                [10, 10],
              ],
              autoLblYn: 'N',
              // confScore 필드 없음
              confidence: null,
              trackId: null,
            },
          ],
        },
        message: null,
        errorCode: null,
      });

      const res = await getLabels(1003);
      expect(res.labels[0].confidence).toBeUndefined();
    });

    it('mapLabelToFrontend_legacy_confidence_필드만_있어도_undefined_null_을_undefined로', async () => {
      // legacy 경로: confScore 누락, confidence 숫자 — 그 값 유지
      mock.onGet('/frames/1004/labels').reply(200, {
        success: true,
        data: {
          frameNo: 1,
          srcSn: 1004,
          siblings: [],
          items: [
            {
              id: 44,
              lblTypeCd: 'BBOX',
              label: 'person',
              points: [
                [0, 0],
                [10, 10],
              ],
              autoLblYn: 'Y',
              confidence: 0.72, // legacy
              trackId: null,
            },
            {
              id: 45,
              lblTypeCd: 'BBOX',
              label: 'car',
              points: [
                [0, 0],
                [10, 10],
              ],
              autoLblYn: 'N',
              // confScore / confidence 모두 없음
              trackId: null,
            },
          ],
        },
        message: null,
        errorCode: null,
      });

      const res = await getLabels(1004);
      expect(res.labels[0].confidence).toBe(0.72);
      expect(res.labels[1].confidence).toBeUndefined();
    });
  });

  it('api_lblSrcCd_누락_응답은_null_정규화', async () => {
    mock.onGet('/frames/890/labels').reply(200, {
      success: true,
      data: {
        frameNo: 4,
        srcSn: 890,
        siblings: [],
        items: [
          {
            id: 31,
            lblTypeCd: 'BBOX',
            label: 'car',
            points: [
              [0, 0],
              [10, 10],
            ],
            autoLblYn: 'Y',
            confScore: 0.8,
            trackId: null,
            // lblSrcCd 필드 자체가 응답에 없는 케이스
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    const res = await getLabels(890);
    expect(res.labels).toHaveLength(1);
    expect(res.labels[0].lblSrcCd == null).toBe(true);
  });

  it('putLabels_PUT_body에_items_배열_포함', async () => {
    // BE 계약: PUT /frames/{srcSn}/labels — body { items: LabelItemDto[] }.
    // FE serializeLabel 은 임시 id('tmp1') 를 null 로, BBOX shape 을 points [[l,t],[r,b]] 로 직렬화한다.
    const labels = [bbox('tmp1', 1)];
    mock.onPut('/frames/777/labels').reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      expect(body).toMatchObject({
        items: [
          {
            id: null,
            lblTypeCd: 'BBOX',
            label: 'car',
            points: [
              [10, 20],
              [100, 80],
            ],
          },
        ],
      });
      return [
        200,
        {
          success: true,
          data: { frameNo: 1, srcSn: 777, labels },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await putLabels(777, labels);
    expect(res.labels).toHaveLength(1);
  });

  it('getLabels_raw_true_옵션_지정시_쿼리_파라미터_raw_true_전달', async () => {
    mock
      .onGet('/frames/901/labels', { params: { raw: true } })
      .reply(200, {
        success: true,
        data: {
          frameNo: 1,
          srcSn: 901,
          videoId: 7,
          frameImageType: 'RAW',
          siblings: [],
          labels: [],
        },
        message: null,
        errorCode: null,
      });

    const res = await getLabels(901, { raw: true });
    expect(res.srcSn).toBe(901);
    expect(res.frameImageType).toBe('RAW');
    expect(mock.history.get).toHaveLength(1);
    expect(mock.history.get[0].params).toEqual({ raw: true });
  });

  it('getLabels_응답에_frameImageType_DEID_포함시_정규화_노출', async () => {
    mock.onGet('/frames/902/labels').reply(200, {
      success: true,
      data: {
        frameNo: 1,
        srcSn: 902,
        videoId: 7,
        frameImageType: 'DEID',
        siblings: [],
        labels: [],
      },
      message: null,
      errorCode: null,
    });

    const res = await getLabels(902);
    expect(res.frameImageType).toBe('DEID');
  });

  it('getLabels_응답에_lockSttsCd_LOCKED_FOR_REDEIDENT_포함시_정규화_노출', async () => {
    mock.onGet('/frames/903/labels').reply(200, {
      success: true,
      data: {
        frameNo: 1,
        srcSn: 903,
        videoId: 7,
        frameImageType: 'DEID',
        lockSttsCd: 'LOCKED_FOR_REDEIDENT',
        siblings: [],
        labels: [],
      },
      message: null,
      errorCode: null,
    });

    const res = await getLabels(903);
    expect(res.lockSttsCd).toBe('LOCKED_FOR_REDEIDENT');
  });

  it('getLabels_응답에_lockSttsCd_누락_시_null_또는_undefined', async () => {
    mock.onGet('/frames/904/labels').reply(200, {
      success: true,
      data: { frameNo: 1, srcSn: 904, siblings: [], labels: [] },
      message: null,
      errorCode: null,
    });

    const res = await getLabels(904);
    expect(res.lockSttsCd == null).toBe(true);
  });

  describe('SAM2 세그/오토라벨 mock 재배선 (message 보존)', () => {
    it('requestSam2Segment_빈폴리곤_mock응답의_ApiResponse_message를_보존', async () => {
      mock.onPost('/frames/7/sam2-segment').reply(200, {
        success: true,
        data: { polygon: [], score: 0 },
        message: 'AI 모델 미로드 — 결과 신뢰 불가',
        errorCode: null,
      });

      const res = await requestSam2Segment(7, { points: [[1, 2]] });
      expect(res.polygon).toEqual([]);
      expect(res.message).toBe('AI 모델 미로드 — 결과 신뢰 불가');
    });

    it('requestSam2Segment_정상응답이면_message_null', async () => {
      mock.onPost('/frames/8/sam2-segment').reply(200, {
        success: true,
        data: { polygon: [[1, 1], [2, 2], [1, 2]], score: 0.9 },
        message: null,
        errorCode: null,
      });

      const res = await requestSam2Segment(8, { box: [1, 1, 2, 2] });
      expect(res.polygon).toHaveLength(3);
      expect(res.message ?? null).toBeNull();
    });

    it('requestAutolabel_내부mock응답의_ApiResponse_message를_보존', async () => {
      mock.onPost('/frames/9/autolabel').reply(200, {
        success: true,
        data: { srcSn: 9, savedCount: 0, labels: [] },
        message: 'AI 모델 미로드 — 결과 신뢰 불가',
        errorCode: null,
      });

      const res = await requestAutolabel(9);
      expect(res.savedCount).toBe(0);
      expect(res.message).toBe('AI 모델 미로드 — 결과 신뢰 불가');
    });

    it('응답타입에_mock필드_없음_런타임_확인', async () => {
      // 컴파일타임: Sam2SegmentResponse/AutolabelResponse 에 mock 필드가 없다(tsc 가드).
      // 런타임: 실제 반환 객체에도 mock 프로퍼티가 없음.
      mock.onPost('/frames/10/sam2-segment').reply(200, {
        success: true,
        data: { polygon: [], score: 0 },
        message: null,
        errorCode: null,
      });
      const seg: Sam2SegmentResponse = await requestSam2Segment(10, { points: [[1, 1]] });
      expect('mock' in seg).toBe(false);

      mock.onPost('/frames/11/autolabel').reply(200, {
        success: true,
        data: { srcSn: 11, savedCount: 0, labels: [] },
        message: null,
        errorCode: null,
      });
      const auto: AutolabelResponse = await requestAutolabel(11);
      expect('mock' in auto).toBe(false);
    });
  });

  describe('reportDeidentMiss', () => {
    it('reportDeidentMiss_POST_labels_srcSn_deident_report_body_reason_포함', async () => {
      mock.onPost('/labels/555/deident-report').reply((config) => {
        const body = JSON.parse(config.data ?? '{}');
        expect(body).toEqual({ reason: '얼굴 미블러' });
        return [
          201,
          { success: true, data: 555, message: null, errorCode: null },
        ];
      });

      const res = await reportDeidentMiss(555, '얼굴 미블러');
      expect(res).toBe(555);
    });

    it('reportDeidentMiss_409_LOCKED_FOR_REDEIDENT_에러_throw', async () => {
      mock.onPost('/labels/556/deident-report').reply(409, {
        success: false,
        data: null,
        message: '이미 잠금',
        errorCode: 'LOCKED_FOR_REDEIDENT',
      });

      await expect(reportDeidentMiss(556, '사유')).rejects.toThrow();
    });

    it('reportDeidentMiss_403_FORBIDDEN_에러_throw', async () => {
      mock.onPost('/labels/557/deident-report').reply(403, {
        success: false,
        data: null,
        message: '권한 없음',
        errorCode: 'FORBIDDEN',
      });

      await expect(reportDeidentMiss(557, '사유')).rejects.toThrow();
    });
  });

  describe('KEYPOINT(SKELETON) 직렬화/정규화', () => {
    function keypointLabel(id: string): Label {
      // 17관절 삼중값 — x,y 는 index 기반, v 는 2/1/0 을 섞어 왕복 보존 검증.
      const keypoints = Array.from({ length: 17 }, (_, i) => ({
        x: i * 2,
        y: i * 3,
        v: i % 3 === 0 ? 2 : i % 3 === 1 ? 1 : 0,
      }));
      return {
        id,
        frameNo: 1,
        classId: 2,
        className: 'person',
        source: 'MANUAL',
        shape: { type: 'KEYPOINT', keypoints },
      };
    }

    it('serializeLabel_KEYPOINT_SKELETON_삼중값_직렬화', async () => {
      const labels = [keypointLabel('kp-tmp')];
      mock.onPut('/frames/778/labels').reply((config) => {
        const body = JSON.parse(config.data ?? '{}');
        const item = body.items[0];
        expect(item.lblTypeCd).toBe('SKELETON');
        expect(item.points).toHaveLength(17);
        // 각 원소가 [x,y,v] 삼중값
        expect(item.points[0]).toEqual([0, 0, 2]);
        expect(item.points[1]).toEqual([2, 3, 1]);
        expect(item.points[2]).toEqual([4, 6, 0]);
        item.points.forEach((p: number[]) => expect(p).toHaveLength(3));
        return [
          200,
          {
            success: true,
            data: { frameNo: 1, srcSn: 778, labels },
            message: null,
            errorCode: null,
          },
        ];
      });
      await putLabels(778, labels);
      expect(mock.history.put).toHaveLength(1);
    });

    it('normalizeLabel_SKELETON_KeypointShape_복원', () => {
      const raw = {
        id: 55,
        lblTypeCd: 'SKELETON',
        label: 'person',
        autoLblYn: 'N',
        points: Array.from({ length: 17 }, (_, i) => [i * 2, i * 3, i % 3 === 0 ? 2 : i % 3 === 1 ? 1 : 0]),
      };
      const label = normalizeLabel(raw);
      expect(label.shape.type).toBe('KEYPOINT');
      const shape = label.shape as KeypointShape;
      expect(shape.keypoints).toHaveLength(17);
      expect(shape.keypoints[0]).toEqual({ x: 0, y: 0, v: 2 });
      expect(shape.keypoints[1]).toEqual({ x: 2, y: 3, v: 1 });
      expect(shape.keypoints[2]).toEqual({ x: 4, y: 6, v: 0 });
    });

    it('SKELETON_왕복_무손실_serialize_후_normalize', async () => {
      const original = keypointLabel('kp-rt');
      let captured: number[][] = [];
      mock.onPut('/frames/779/labels').reply((config) => {
        const body = JSON.parse(config.data ?? '{}');
        captured = body.items[0].points;
        return [
          200,
          { success: true, data: { frameNo: 1, srcSn: 779, labels: [] }, message: null, errorCode: null },
        ];
      });
      await putLabels(779, [original]);
      // BE round-trip 을 모사: 직렬화된 points 를 그대로 normalizeLabel 에 재입력.
      const restored = normalizeLabel({ id: 55, lblTypeCd: 'SKELETON', label: 'person', autoLblYn: 'N', points: captured });
      const shape = restored.shape as KeypointShape;
      const origShape = original.shape as KeypointShape;
      expect(shape.keypoints).toEqual(origShape.keypoints);
    });

    it('normalizeLabel_SKELETON_v_범위밖_값은_0_1_2로_클램프', () => {
      const raw = {
        id: 60,
        lblTypeCd: 'SKELETON',
        label: 'person',
        autoLblYn: 'N',
        points: Array.from({ length: 17 }, () => [10, 20, 5]), // v=5 (범위 밖)
      };
      const shape = normalizeLabel(raw).shape as KeypointShape;
      shape.keypoints.forEach((kp) => expect([0, 1, 2]).toContain(kp.v));
    });
  });

  describe('saveAndCommit', () => {
    it('저장_시_PUT_labels_단일_호출_BE가_커밋_통합_처리', async () => {
      // BE 계약: PUT /frames/{srcSn}/labels 가 저장 + 라벨 스냅샷 버전 커밋(DB)을 한 번에 처리.
      // 별도 POST /frames/{srcSn}/commit 호출 없음. committed 는 null 로 반환.
      const calls: string[] = [];
      mock.onPut('/frames/777/labels').reply(() => {
        calls.push('PUT');
        return [
          200,
          {
            success: true,
            data: { frameNo: 1, srcSn: 777, labels: [] },
            message: null,
            errorCode: null,
          },
        ];
      });

      const result = await saveAndCommit(777, [bbox('a', 1)]);
      expect(calls).toEqual(['PUT']);
      expect(result.committed).toBeNull();
      expect(result.saved.srcSn).toBe(777);
    });

    it('portalMode_저장시에도_PUT_단일_호출', async () => {
      // portalMode 여부와 무관하게 단일 PUT. BE 가 채널에 따라 commit skip 처리.
      const calls: string[] = [];
      mock.onPut('/frames/777/labels').reply(() => {
        calls.push('PUT');
        return [
          200,
          {
            success: true,
            data: { frameNo: 1, srcSn: 777, labels: [] },
            message: null,
            errorCode: null,
          },
        ];
      });

      const result = await saveAndCommit(777, [bbox('a', 1)], { portalMode: true });
      expect(calls).toEqual(['PUT']);
      expect(result.committed).toBeNull();
    });
  });
});
