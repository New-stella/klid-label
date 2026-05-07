import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { commitLabels, getLabels, putLabels } from '../api';
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
