import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { commitLabels, getLabels, putLabels, reportDeidentMiss } from '../api';
import { saveAndCommit } from '../SaveCommitFlow';
import type { Label } from '../types';

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

  it('putLabels_PUT_body에_labels_배열_포함', async () => {
    const labels = [bbox('tmp1', 1)];
    mock.onPut('/frames/777/labels').reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      expect(body).toMatchObject({ labels: [{ id: 'tmp1', shape: { type: 'BBOX' } }] });
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

  it('commitLabels_POST_frames_srcSn_commit', async () => {
    mock.onPost('/frames/777/commit').reply(200, {
      success: true,
      data: { commitSha: 'abc123', committedAt: '2026-05-07T10:00:00Z' },
      message: null,
      errorCode: null,
    });

    const res = await commitLabels(777, 'edit');
    expect(res.commitSha).toBe('abc123');
  });

  describe('saveAndCommit', () => {
    it('저장_시_PUT_labels_→_POST_commit_순서', async () => {
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
      mock.onPost('/frames/777/commit').reply(() => {
        calls.push('POST');
        return [
          200,
          {
            success: true,
            data: { commitSha: 'sha', committedAt: '2026-05-07' },
            message: null,
            errorCode: null,
          },
        ];
      });

      const result = await saveAndCommit(777, [bbox('a', 1)]);
      expect(calls).toEqual(['PUT', 'POST']);
      expect(result.committed?.commitSha).toBe('sha');
    });

    it('portalMode_저장시_commit_호출_안_함', async () => {
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
      mock.onPost('/frames/777/commit').reply(() => {
        calls.push('POST');
        return [200, { success: true, data: {}, message: null, errorCode: null }];
      });

      const result = await saveAndCommit(777, [bbox('a', 1)], { portalMode: true });
      expect(calls).toEqual(['PUT']);
      expect(result.committed).toBeNull();
    });
  });
});
