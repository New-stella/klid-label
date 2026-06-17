import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getMeta, updateMeta } from '../api';

describe('auto api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('메타_조회시_BE_items_KV_응답을_vlmText로_결합', async () => {
    // given — BE(SoT) 실제 응답: items K/V 목록 (metaKey 정렬키)
    mock.onGet('/frames/100/meta').reply(200, {
      success: true,
      data: {
        items: [
          { metaSn: 2, metaKey: '0002', metaVal: '09:05 화재 감지' },
          { metaSn: 1, metaKey: '0001', metaVal: '09:00 맑음, 차량 3대 진입' },
        ],
      },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getMeta(100);

    // then — metaKey 오름차순으로 결합
    expect(r.items).toHaveLength(2);
    expect(r.vlmText).toBe('09:00 맑음, 차량 3대 진입\n09:05 화재 감지');
    expect(r.stateChanges).toEqual([]);
  });

  it('메타_조회_items_0건이면_vlmText_빈문자_stateChanges_빈배열', async () => {
    // given — VLM 메타 0건 (RED: 과거엔 undefined.length 크래시 유발)
    mock.onGet('/frames/22/meta').reply(200, {
      success: true,
      data: { items: [] },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getMeta(22);

    // then
    expect(r.items).toEqual([]);
    expect(r.vlmText).toBe('');
    expect(r.stateChanges).toEqual([]);
  });

  it('메타_저장시_PUT_items_KV_전송', async () => {
    // given — BE 는 기존 metaKey 값만 수정 → items[{metaKey, metaVal}] 전송
    mock.onPut('/frames/100/meta').reply((config) => {
      const body = JSON.parse(config.data);
      expect(body.items).toEqual([{ metaKey: '0001', metaVal: '09:00 맑음, 수정됨' }]);
      return [
        200,
        {
          success: true,
          data: { items: [{ metaSn: 1, metaKey: '0001', metaVal: '09:00 맑음, 수정됨' }] },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    const r = await updateMeta(100, {
      items: [{ metaKey: '0001', metaVal: '09:00 맑음, 수정됨' }],
    });

    // then
    expect(r.vlmText).toBe('09:00 맑음, 수정됨');
  });
});
