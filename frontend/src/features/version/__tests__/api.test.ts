import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getDiff, listVersions, rollback } from '../api';

describe('version api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('listVersions_GET_videos_id_versions_커밋_시간순_정렬', async () => {
    mock.onGet('/videos/777/versions').reply(200, {
      success: true,
      data: [
        {
          commitSha: 'aaa111',
          shortHash: 'aaa111',
          authorName: '홍길동',
          message: '라벨 수정',
          committedAt: '2026-05-07T10:00:00Z',
          isCurrent: true,
        },
        {
          commitSha: 'bbb222',
          shortHash: 'bbb222',
          authorName: '김검수',
          message: '초기 라벨',
          committedAt: '2026-05-06T10:00:00Z',
          isCurrent: false,
        },
      ],
      message: null,
      errorCode: null,
    });

    const res = await listVersions(777);
    expect(res).toHaveLength(2);
    expect(res[0].commitSha).toBe('aaa111');
    expect(res[0].isCurrent).toBe(true);
  });

  it('getDiff_GET_versions_commit_diff_쿼리_compareWith', async () => {
    mock.onGet('/versions/aaa111/diff').reply((config) => {
      expect(config.params).toMatchObject({ compareWith: 'bbb222' });
      return [
        200,
        {
          success: true,
          data: [
            {
              type: 'ADDED',
              frameId: 1,
              objectId: 'obj-1',
              after: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
            },
            {
              type: 'MODIFIED',
              frameId: 1,
              objectId: 'obj-2',
              before: { type: 'BBOX', left: 0, top: 0, right: 5, bottom: 5 },
              after: { type: 'BBOX', left: 0, top: 0, right: 8, bottom: 8 },
            },
            {
              type: 'REMOVED',
              frameId: 2,
              objectId: 'obj-3',
              before: { type: 'BBOX', left: 1, top: 1, right: 3, bottom: 3 },
            },
          ],
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await getDiff('aaa111', 'bbb222');
    expect(res).toHaveLength(3);
    expect(res[0].type).toBe('ADDED');
  });

  it('rollback_POST_versions_commit_rollback', async () => {
    mock.onPost('/versions/bbb222/rollback').reply((config) => {
      expect(config.url).toBe('/versions/bbb222/rollback');
      return [
        200,
        {
          success: true,
          data: { newCommitSha: 'ccc333', rolledBackFrom: 'bbb222' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await rollback('bbb222');
    expect(res.newCommitSha).toBe('ccc333');
  });
});
