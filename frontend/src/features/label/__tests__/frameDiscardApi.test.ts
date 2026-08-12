// R4·R5 — 프레임 폐기·복원은 별도 엔드포인트가 아니라 **기존 라벨 저장 계약의 선택 필드**다.
// 화면에서 한 일은 저장을 눌러야 확정된다(D8).
//
// ⚠ "보내지 않으면 현재 값 유지" 가 계약의 핵심이다 — 폐기를 모르는 호출자가 저장할 때마다
//   폐기 상태를 조용히 되돌리면 안 된다(하위호환).

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getLabels, putLabels } from '../api';
import { DscdYn } from '../types';

describe('프레임 폐기·복원 — 라벨 저장 계약', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  const okBody = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

  it('폐기여부를_지정하면_저장_요청에_실어_보낸다', async () => {
    // given
    let sent: Record<string, unknown> = {};
    mock.onPut('/frames/5/labels').reply((config) => {
      sent = JSON.parse(config.data as string) as Record<string, unknown>;
      return [200, okBody({ srcSn: 5, frameNo: 0, siblings: [], items: [] })];
    });

    // when
    await putLabels(5, [], 3, DscdYn.Y);

    // then
    expect(sent.dscdYn).toBe('Y');
  });

  it('폐기여부를_지정하지_않으면_필드_자체를_보내지_않는다', async () => {
    // given: 필드를 보내면 BE 가 "현재 값 유지" 경로가 아니라 명시 지정으로 해석한다.
    let sent: Record<string, unknown> = {};
    mock.onPut('/frames/5/labels').reply((config) => {
      sent = JSON.parse(config.data as string) as Record<string, unknown>;
      return [200, okBody({ srcSn: 5, frameNo: 0, siblings: [], items: [] })];
    });

    // when
    await putLabels(5, [], 3);

    // then
    expect('dscdYn' in sent).toBe(false);
  });

  it('라벨_응답의_현재_프레임_폐기여부를_읽는다', async () => {
    // given
    mock.onGet('/frames/5/labels').reply(
      200,
      okBody({ srcSn: 5, frameNo: 2, videoId: 9, dscdYn: 'Y', siblings: [], items: [] }),
    );

    // when
    const res = await getLabels(5);

    // then
    expect(res.dscdYn).toBe('Y');
  });

  it('폐기_축을_싣지_않은_응답은_null_이며_폐기_아님으로_단정하지_않는다', async () => {
    // given: BE 는 폐기 축을 싣지 않는 경로에서 필드를 생략한다(NON_NULL).
    mock.onGet('/frames/5/labels').reply(
      200,
      okBody({ srcSn: 5, frameNo: 2, videoId: 9, siblings: [], items: [] }),
    );

    // when
    const res = await getLabels(5);

    // then: 'N' 으로 채우지 않는다 — 모름과 폐기 아님은 다른 사실이다.
    expect(res.dscdYn).toBeNull();
  });

  it('형제_프레임의_폐기여부도_읽는다', async () => {
    // given
    mock.onGet('/frames/5/labels').reply(
      200,
      okBody({
        srcSn: 5,
        frameNo: 0,
        videoId: 9,
        dscdYn: 'N',
        siblings: [
          { srcSn: 5, frameNo: 0, hasLabel: true, dscdYn: 'N' },
          { srcSn: 6, frameNo: 1, hasLabel: false, dscdYn: 'Y' },
          { srcSn: 7, frameNo: 2, hasLabel: false },
        ],
        items: [],
      }),
    );

    // when
    const res = await getLabels(5);

    // then
    expect(res.siblings.map((s) => s.dscdYn)).toEqual(['N', 'Y', null]);
  });

  it('알_수_없는_폐기여부_값은_null_로_떨어진다', async () => {
    // given: 코드값 컬럼이라 Y/N 뿐이다. 그 밖의 값은 신뢰하지 않는다(fail-closed —
    //        '폐기됨'으로 오인해 편집을 잠그면 작업이 막힌다).
    mock.onGet('/frames/5/labels').reply(
      200,
      okBody({ srcSn: 5, frameNo: 0, videoId: 9, dscdYn: 'MAYBE', siblings: [], items: [] }),
    );

    // when
    const res = await getLabels(5);

    // then
    expect(res.dscdYn).toBeNull();
  });
});
