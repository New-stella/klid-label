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

  // versionHash = 라벨 스냅샷 SHA-256 hex(64자). 응답 필드명 commitSha/shortHash 는 와이어 호환 유지.
  const HASH_NEW = 'a'.repeat(64);
  const HASH_OLD = 'b'.repeat(64);

  it('listVersions_GET_frames_srcSn_versions_시간_역순_versionHash_스키마', async () => {
    mock.onGet('/frames/777/versions').reply(200, {
      success: true,
      data: [
        {
          commitSha: HASH_NEW,
          shortHash: HASH_NEW.slice(0, 7),
          authorName: '홍길동',
          message: 'MANUAL',
          committedAt: '2026-05-07T10:00:00Z',
          isCurrent: true,
        },
        {
          commitSha: HASH_OLD,
          shortHash: HASH_OLD.slice(0, 7),
          authorName: '김검수',
          message: 'BATCH',
          committedAt: '2026-05-06T10:00:00Z',
          isCurrent: false,
        },
      ],
      message: null,
      errorCode: null,
    });

    const res = await listVersions(777);
    expect(res).toHaveLength(2);
    expect(res[0].commitSha).toBe(HASH_NEW);
    expect(res[0].commitSha).toHaveLength(64);
    expect(res[0].shortHash).toHaveLength(7);
    expect(res[0].message).toBe('MANUAL');
    expect(res[0].isCurrent).toBe(true);
    expect(res[1].isCurrent).toBe(false);
  });

  it('getDiff_path_toHash_query_fromHash_LabelDiff_스키마', async () => {
    // path = 비교 대상 to(versionHash), query compareWith = 기준 from(versionHash)
    mock.onGet(`/versions/${HASH_NEW}/diff`).reply((config) => {
      expect(config.params).toMatchObject({ compareWith: HASH_OLD });
      return [
        200,
        {
          success: true,
          data: [
            {
              type: 'ADDED',
              frameId: 1,
              objectId: 'obj-1',
              before: null,
              after: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
            },
            {
              type: 'MODIFIED',
              frameId: 1,
              objectId: 'obj-2',
              before: { type: 'BBOX', left: 0, top: 0, right: 5, bottom: 5 },
              after: { type: 'POLYGON', points: [0, 0, 8, 0, 8, 8, 0, 8] },
            },
            {
              type: 'REMOVED',
              frameId: 2,
              objectId: 'obj-3',
              before: { type: 'BBOX', left: 1, top: 1, right: 3, bottom: 3 },
              after: null,
            },
          ],
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await getDiff(HASH_NEW, HASH_OLD);
    expect(res).toHaveLength(3);
    expect(res[0].type).toBe('ADDED');
    expect(res[0].before).toBeNull();
    expect(res[0].after).toMatchObject({ type: 'BBOX' });
    expect(res[1].type).toBe('MODIFIED');
    expect(res[1].after).toMatchObject({ type: 'POLYGON' });
    expect(res[2].type).toBe('REMOVED');
    expect(res[2].after).toBeNull();
  });

  it('rollback_POST_versionHash_rollback_body_srcSn_신규_이력_스키마', async () => {
    let postedBody: unknown = null;
    mock.onPost(`/versions/${HASH_OLD}/rollback`).reply((config) => {
      expect(config.url).toBe(`/versions/${HASH_OLD}/rollback`);
      postedBody = JSON.parse(config.data as string);
      return [
        200,
        {
          success: true,
          data: {
            lblHstrySn: 9001,
            srcSn: 241,
            versionHash: HASH_OLD,
            registeredUserNo: 42,
            registeredAt: '2026-05-29T09:00:00Z',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await rollback(HASH_OLD, 241);
    // body 에 srcSn 전송 확인
    expect(postedBody).toMatchObject({ srcSn: 241 });
    // 신규 이력 결과 스키마 확인
    expect(res.lblHstrySn).toBe(9001);
    expect(res.srcSn).toBe(241);
    expect(res.versionHash).toBe(HASH_OLD);
    expect(res.registeredUserNo).toBe(42);
    expect(res.registeredAt).toBe('2026-05-29T09:00:00Z');
  });
});
