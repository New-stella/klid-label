// R6/D4 — 영상 단위 「시작 버전 선택」 API 계약.
//
// 프레임 단위 버전 계약(listVersions/getDiff/rollback)과 **별개 리소스**다. 여기서 다루는 번호는
// 관제가 픽업하는 산출 폴더 번호(v1·v2)와 같은 값이며 영상 단위로 매겨진다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { applyStartVersion, listVideoVersions } from '../api';

describe('영상 단위 산출 버전 API', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('영상_산출_버전_목록을_서버가_준_순서_그대로_반환한다', async () => {
    // given: BE 는 내림차순으로 내려준다(최신이 먼저).
    mock.onGet('/videos/9/versions').reply(200, {
      success: true,
      data: [
        { versionNo: 3, snapshotCnt: 2, latestRegDt: '2026-08-09T10:00:00' },
        { versionNo: 1, snapshotCnt: 5, latestRegDt: '2026-08-01T10:00:00' },
      ],
      message: null,
      errorCode: null,
    });

    // when
    const res = await listVideoVersions(9);

    // then: FE 가 순서를 다시 정하지 않는다 — 정렬 축이 두 곳으로 갈리면 화면과 서버가 어긋난다.
    expect(res.map((v) => v.versionNo)).toEqual([3, 1]);
    expect(res[0].snapshotCnt).toBe(2);
  });

  it('건너뛴_회차_번호를_결손으로_보정하지_않는다', async () => {
    // given: 회차 2 는 모든 프레임 내용이 그대로여서 스냅샷이 없다(정상).
    mock.onGet('/videos/9/versions').reply(200, {
      success: true,
      data: [
        { versionNo: 3, snapshotCnt: 1, latestRegDt: '2026-08-09T10:00:00' },
        { versionNo: 1, snapshotCnt: 4, latestRegDt: '2026-08-01T10:00:00' },
      ],
      message: null,
      errorCode: null,
    });

    // when
    const res = await listVideoVersions(9);

    // then: 없는 2 를 만들어 채우지 않는다.
    expect(res).toHaveLength(2);
    expect(res.some((v) => v.versionNo === 2)).toBe(false);
  });

  it('비배열_응답은_빈_목록으로_떨어진다', async () => {
    mock.onGet('/videos/9/versions').reply(200, {
      success: true,
      data: null,
      message: null,
      errorCode: null,
    });

    await expect(listVideoVersions(9)).resolves.toEqual([]);
  });

  it('시작버전_적용은_versionNo_를_바디에_실어_PUT_한다', async () => {
    // given
    let sentBody: unknown = null;
    mock.onPut('/videos/9/start-version').reply((config) => {
      sentBody = JSON.parse(config.data as string);
      return [
        200,
        {
          success: true,
          data: {
            rawSn: 9,
            versionNo: 1,
            totalFrames: 10,
            appliedFrames: 8,
            revivedFrames: 2,
            discardedFrames: 1,
            unresolvedFrames: 2,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    const res = await applyStartVersion(9, 1);

    // then
    expect(sentBody).toEqual({ versionNo: 1 });
    expect(res.appliedFrames).toBe(8);
    // 되돌리지 못한 프레임 수는 숨기지 않는다 — 숨기면 화면이 "전부 되돌렸다"고 거짓말한다.
    expect(res.unresolvedFrames).toBe(2);
  });
});
