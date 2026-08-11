// R6/D4 — 영상 단위 「시작 버전 선택」 API 계약.
//
// 프레임 단위 버전 계약(listVersions/getDiff/rollback)과 **별개 리소스**다. 여기서 다루는 번호는
// 관제가 픽업하는 산출 폴더 번호(v1·v2)와 같은 값이며 영상 단위로 매겨진다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getVersionLabels, listVideoVersions, saveVideoLabels } from '../api';

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

  it('불러오기는_회차를_경로에_담아_GET_한다_바디가_없다', async () => {
    // given — API-195 는 읽기 전용이다. 쓰기 메서드(PUT/POST)로 바뀌면 서버 상태가 바뀌는 축이 된다.
    let method: string | undefined;
    let url: string | undefined;
    mock.onGet('/videos/9/versions/2/labels').reply((config) => {
      method = config.method;
      url = config.url;
      return [
        200,
        {
          success: true,
          data: {
            rawSn: 9,
            version: 2,
            frames: [
              {
                srcSn: 51,
                frmNo: 0,
                dscdYn: 'Y',
                lblVer: 7,
                resolved: true,
                items: [
                  { id: 9001, lblTypeCd: 'BBOX', label: '사람', labelId: 12, points: [[1, 2], [3, 4]] },
                ],
              },
            ],
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    const res = await getVersionLabels(9, 2);

    // then
    expect(method).toBe('get');
    expect(url).toBe('/videos/9/versions/2/labels');
    expect(res.version).toBe(2);
    expect(res.frames[0].dscdYn).toBe('Y');
    // 확정 저장에 되돌려 보낼 판번호가 함께 온다 — 없으면 전수 검증이 성립하지 않는다.
    expect(res.frames[0].lblVer).toBe(7);
    // ★ labelId 를 잃으면 저장 후 라벨 마스터 조인이 끊긴다.
    expect(res.frames[0].items[0].labelId).toBe(12);
  });

  it('불러오기_응답의_frames_가_배열이_아니면_빈_목록으로_떨어진다', async () => {
    mock.onGet('/videos/9/versions/2/labels').reply(200, {
      success: true,
      data: { rawSn: 9, version: 2, frames: null },
      message: null,
      errorCode: null,
    });

    await expect(getVersionLabels(9, 2)).resolves.toEqual({ rawSn: 9, version: 2, frames: [] });
  });

  it('확정_저장은_영상_경로에_프레임_전체를_실어_PUT_한다', async () => {
    // given
    let sentBody: Record<string, unknown> | null = null;
    mock.onPut('/videos/9/labels').reply((config) => {
      sentBody = JSON.parse(config.data as string);
      return [
        200,
        {
          success: true,
          data: {
            rawSn: 9,
            frames: [{ srcSn: 51, dscdYn: 'N', lblVer: 8 }],
            savedFrameCount: 1,
            discardedFrameCount: 0,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    const res = await saveVideoLabels(9, {
      frames: [{ srcSn: 51, lblVer: 7, dscdYn: 'N', items: [] }],
      loadedVersion: '2',
    });

    // then
    expect(sentBody).toEqual({
      frames: [{ srcSn: 51, lblVer: 7, dscdYn: 'N', items: [] }],
      loadedVersion: '2',
    });
    expect(res.savedFrameCount).toBe(1);
    // 다음 저장에 쓸 판번호가 응답에 있다 — 없으면 화면이 곧바로 자기 자신과 409 가 난다.
    expect(res.frames[0].lblVer).toBe(8);
  });

  it('loadedVersion_이_없으면_필드를_보내지_않는다', async () => {
    // given — BE 가 "불러오기를 거치지 않은 평상시 저장"으로 처리해야 한다(감사 대상 아님).
    let sentBody: Record<string, unknown> | null = null;
    mock.onPut('/videos/9/labels').reply((config) => {
      sentBody = JSON.parse(config.data as string);
      return [200, { success: true, data: {}, message: null, errorCode: null }];
    });

    // when
    await saveVideoLabels(9, { frames: [{ srcSn: 51, lblVer: 1, items: [] }] });

    // then
    expect(sentBody).not.toHaveProperty('loadedVersion');
  });
});
