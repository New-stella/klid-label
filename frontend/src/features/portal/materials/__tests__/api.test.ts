/**
 * 회귀 가드 — 소재 조달 창구의 <b>주소와 메서드</b>.
 *
 * ★ <b>주소 축을 본문 축과 따로 단언한다.</b> 요청 모의는 <b>미매치 요청도 이력에 남기므로</b>,
 *   이력의 건수나 본문만 보는 시험은 창구 주소를 통째로 바꾸는 변이를 그대로 통과시킨다.
 *   그래서 `mock.history` 의 `url` 을 직접 못 박는다.
 *
 * ★ 착수는 <b>본문이 없다</b>(경로 변수 하나만 받는 창구). 본문을 실으면 서버 계약과 갈린다.
 *
 * @design INT-014
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getDatasetMaterials, restartDatasetRegistration, startDatasetMaterials } from '../api';
import { PortalMaterialsState } from '../types';

// ⚠ `as const` 를 붙이지 않는다 — 모의 어댑터의 콜백 반환 타입이 **가변 배열**이라 readonly 튜플이
//    들어가지 않는다. 시험은 통과하는데 `tsc -b` 만 빨개진다(실측).
function ok(data: unknown): [number, unknown] {
  return [200, { success: true, data, message: null, errorCode: null }];
}

describe('포털 소재 조달 api', () => {
  let mock: MockAdapter;
  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  it('상태_조회는_데이터셋_경로의_materials_를_GET_한다', async () => {
    mock.onGet('/portal/datasets/4704/materials').reply(() =>
      ok({
        datasetId: 4704,
        state: PortalMaterialsState.READY,
        failureReason: null,
        materials: null,
      }),
    );

    const status = await getDatasetMaterials(4704);

    expect(status.state).toBe(PortalMaterialsState.READY);
    // ★주소 축 — 본문만 보면 창구를 바꿔도 통과한다.
    expect(mock.history.get.map((r) => r.url)).toEqual(['/portal/datasets/4704/materials']);
  });

  it('착수는_같은_주소를_POST_하고_본문을_싣지_않는다', async () => {
    mock.onPost('/portal/datasets/4704/materials').reply(() =>
      ok({
        datasetId: 4704,
        state: PortalMaterialsState.IN_PROGRESS,
        failureReason: null,
        materials: null,
      }),
    );

    const status = await startDatasetMaterials(4704);

    expect(status.state).toBe(PortalMaterialsState.IN_PROGRESS);
    expect(mock.history.post.map((r) => r.url)).toEqual(['/portal/datasets/4704/materials']);
    expect(mock.history.post[0].data).toBeUndefined();
    // 조회 창구를 대신 부르지 않았다(같은 경로라 방향이 바뀌어도 이력 건수로는 안 갈린다).
    expect(mock.history.get).toHaveLength(0);
  });

  it('데이터셋마다_주소가_갈린다_식별자를_무시하는_변이를_잡는다', async () => {
    mock.onGet(/\/portal\/datasets\/\d+\/materials/).reply(() =>
      ok({
        datasetId: 1,
        state: PortalMaterialsState.NOT_PROVISIONED,
        failureReason: null,
        materials: null,
      }),
    );

    await getDatasetMaterials(11);
    await getDatasetMaterials(22);

    expect(mock.history.get.map((r) => r.url)).toEqual([
      '/portal/datasets/11/materials',
      '/portal/datasets/22/materials',
    ]);
  });
  it('등록_재착수는_데이터셋_경로의_registration_을_POST_하고_본문을_싣지_않는다', async () => {
    // [@design API-262]
    mock.onPost('/portal/datasets/4704/registration').reply(() =>
      ok({ registrationState: 'IN_PROGRESS', registrationFailureReason: null }),
    );

    const result = await restartDatasetRegistration(4704);

    expect(result.registrationState).toBe('IN_PROGRESS');
    expect(result.registrationFailureReason).toBeNull();
    // ★주소 축 — 조달 착수 창구(materials)와 다른 창구다.
    expect(mock.history.post.map((r) => r.url)).toEqual(['/portal/datasets/4704/registration']);
    expect(mock.history.post[0].data).toBeUndefined();
  });
});
