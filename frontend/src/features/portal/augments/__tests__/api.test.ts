// 회귀 가드 — 포털 증강 조회 창구 두 개를 **계약대로** 부르는가.
//
// ★ 백엔드가 아직 이 창구를 만들고 있어 라이브로 확인할 수 없다. 그래서 계약(API-232·API-233)이
//   못박은 경로·파라미터·응답 봉투를 목으로 고정한다 — 나중에 서버가 붙었을 때 어긋나면 여기가
//   먼저 빨개진다.
//
// ★★ **접수 창구(API-231)를 이 모듈이 갖지 않는다**는 것도 함께 못박는다. 요청을 거는 자리는
//   증강 화면이 아니라 포털 업로드 화면의 자산별 액션이고, 이 모듈에 접수 함수가 생기는 순간
//   같은 행위의 진입이 둘이 되는 첫 단추가 된다.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import * as augmentApi from '../api';
import { getPortalAugment, listPortalAugments } from '../api';

const OK = (data: unknown) => [200, { success: true, data, message: null, errorCode: null }];

describe('포털 증강 조회 API', () => {
  let mock: MockAdapter;
  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  it('목록은_page_size_쿼리로_portal_augments_를_GET_한다', async () => {
    mock.onGet('/portal/augments').reply((config) => {
      expect(config.params).toMatchObject({ page: 1, size: 20 });
      return OK({ content: [], totalElements: 0, totalPages: 0, number: 1, size: 20 });
    });

    const page = await listPortalAugments({ page: 1, size: 20 });
    expect(page.content).toEqual([]);
    expect(mock.history.get).toHaveLength(1);
  });

  it('★정렬_파라미터를_보내지_않는다_계약이_요청_일시_내림차순으로_고정했다', async () => {
    mock.onGet('/portal/augments').reply(() =>
      OK({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }),
    );

    await listPortalAugments({ page: 0, size: 20 });

    const sent = mock.history.get[0].params as Record<string, unknown>;
    expect(Object.keys(sent).sort()).toEqual(['page', 'size']);
  });

  it('단건은_요청_식별자를_경로에_실어_GET_하고_결과물_자산_식별자를_돌려준다', async () => {
    mock.onGet('/portal/augments/9001').reply(() =>
      OK({
        augSn: 9001,
        uldSn: 501,
        augSttsCd: 'ACCEPTED',
        failRsnCn: null,
        orgnlFileNm: 'street.mp4',
        requestedAt: '2026-09-01T10:00:00',
        resultReady: true,
        resultUldSn: 802,
        resultArrivedAt: '2026-09-01T10:42:00',
        generationCondition: {},
      }),
    );

    const detail = await getPortalAugment(9001);
    expect(detail.resultUldSn).toBe(802);
    expect(detail.resultReady).toBe(true);
  });

  it('★이_모듈은_조회_둘만_내보낸다_접수_창구는_업로드_화면이_갖는다', () => {
    expect(Object.keys(augmentApi).sort()).toEqual(['getPortalAugment', 'listPortalAugments']);
  });
});
