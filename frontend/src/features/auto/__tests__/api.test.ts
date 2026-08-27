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

  // ───────── video.* 기술메타 분리 (2026-08-03) ─────────

  it('메타_조회시_BE_technicalMeta는_시계열items와_분리보존', async () => {
    // given — BE 가 이미 분리해 내려주는 정상 응답
    mock.onGet('/frames/101/meta').reply(200, {
      success: true,
      data: {
        items: [{ metaSn: 1, metaKey: '0-10', metaVal: '차량 3대 진입' }],
        technicalMeta: [
          { metaSn: 9, metaKey: 'video.fps', metaVal: '30' },
          { metaSn: 10, metaKey: 'video.resolution', metaVal: '1920x1080' },
        ],
      },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getMeta(101);

    // then — 시계열 텍스트에 기술메타 값이 섞이지 않고, 기술메타는 별도로 보존된다.
    expect(r.items.map((i) => i.metaKey)).toEqual(['0-10']);
    expect(r.vlmText).toBe('차량 3대 진입');
    expect(r.technicalMeta.map((i) => i.metaKey)).toEqual([
      'video.fps',
      'video.resolution',
    ]);
  });

  it('구BE_items에_video키가_섞여와도_시계열에서_제외하고_기술메타로_분류', async () => {
    // given — 배포 스큐(구 BE: technicalMeta 필드 없음, items 에 video.* 혼재)
    mock.onGet('/frames/102/meta').reply(200, {
      success: true,
      data: {
        items: [
          { metaSn: 1, metaKey: '0-10', metaVal: '차량 3대 진입' },
          { metaSn: 9, metaKey: 'video.fps', metaVal: '30' },
        ],
      },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getMeta(102);

    // then — FE 도 방어적으로 걸러 시계열 텍스트 오염을 막는다.
    expect(r.items.map((i) => i.metaKey)).toEqual(['0-10']);
    expect(r.vlmText).toBe('차량 3대 진입');
    expect(r.technicalMeta.map((i) => i.metaKey)).toEqual(['video.fps']);
  });

  it('technicalMeta_누락응답도_빈배열로_안전기본값', async () => {
    // given
    mock.onGet('/frames/103/meta').reply(200, {
      success: true,
      data: { items: [] },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getMeta(103);

    // then
    expect(r.technicalMeta).toEqual([]);
  });

  // ───────── readOnlyMeta 분리 + 레거시 구간 숫자 정렬 (2026-08-06, R8/R9) ─────────

  it('readOnlyMeta는_items에서_분리되어_보존된다', async () => {
    // given — verify 전환 후 BE 응답: 서술 전문(items) + 일치도(readOnlyMeta)
    mock.onGet('/frames/110/meta').reply(200, {
      success: true,
      data: {
        items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '차량이 정지선을 넘었다' }],
        readOnlyMeta: [{ metaSn: 2, metaKey: 'vlm.accuracy', metaVal: '0.92' }],
        technicalMeta: [],
      },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getMeta(110);

    // then — 일치도가 편집 목록(items)·시계열 텍스트에 섞이지 않고 별도 보존된다.
    expect(r.items.map((i) => i.metaKey)).toEqual(['vlm.description']);
    expect(r.readOnlyMeta.map((i) => i.metaKey)).toEqual(['vlm.accuracy']);
    expect(r.vlmText).toBe('차량이 정지선을 넘었다');
  });

  it('readOnlyMeta_누락응답도_빈배열로_안전기본값', async () => {
    // given — 구 BE(배포 스큐) / 로딩 직후
    mock.onGet('/frames/111/meta').reply(200, {
      success: true,
      data: { items: [] },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getMeta(111);

    // then
    expect(r.readOnlyMeta).toEqual([]);
  });

  // ───────── importedMeta(이관 원문) 보존 (2026-08-27) [design: API-066] ─────────

  it('importedMeta는_어댑터가_버리지_않고_그대로_실어_나른다', async () => {
    // given — 서버가 이관 원문을 네 번째 목록으로 갈라 내려준다.
    //   ★어댑터가 알 수 없는 필드를 버리면 이관 영상의 메타가 화면에서 <b>조용히 사라진다</b>
    //   (깨지는 것이 아니라 안 보이는 것이라 발견이 늦다).
    mock.onGet('/frames/120/meta').reply(200, {
      success: true,
      data: {
        items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '연기가 보인다' }],
        readOnlyMeta: [],
        technicalMeta: [],
        importedMeta: [
          { metaSn: 5, metaKey: 'import.video.cctv_height', metaVal: '4.5' },
          { metaSn: 6, metaKey: 'import.video.location', metaVal: '강남대로' },
        ],
      },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getMeta(120);

    // then — 새 목록이 보존되고, 기존 세 목록의 시맨틱은 그대로다.
    expect(r.importedMeta.map((i) => i.metaKey)).toEqual([
      'import.video.cctv_height',
      'import.video.location',
    ]);
    expect(r.importedMeta.map((i) => i.metaVal)).toEqual(['4.5', '강남대로']);
    // then — 이관 원문이 시계열 목록·결합 텍스트로 새지 않는다.
    expect(r.items.map((i) => i.metaKey)).toEqual(['vlm.description']);
    expect(r.vlmText).toBe('연기가 보인다');
  });

  it('importedMeta_누락응답도_빈배열로_안전기본값', async () => {
    // given — 구 BE(배포 스큐) / 로딩 직후. 이관으로 들어오지 않은 영상도 같은 모양이다.
    mock.onGet('/frames/121/meta').reply(200, {
      success: true,
      data: { items: [] },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getMeta(121);

    // then — null 이 아니라 빈 배열이라 화면이 분기 없이 그린다.
    expect(r.importedMeta).toEqual([]);
  });

  it('레거시_구간은_start_sec_숫자순으로_결합된다', async () => {
    // given — 구간 10개 초과. 문자열 정렬이면 '10-18' 이 '8-16' 앞에 온다(시간순 파괴).
    const keys = [
      '72-80',
      '0-8',
      '16-24',
      '8-16',
      '80-88',
      '24-32',
      '32-40',
      '40-48',
      '48-56',
      '56-64',
      '64-72',
    ];
    mock.onGet('/frames/112/meta').reply(200, {
      success: true,
      data: {
        items: keys.map((k, i) => ({ metaSn: i + 1, metaKey: k, metaVal: k })),
      },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getMeta(112);

    // then
    expect(r.vlmText.split('\n')).toEqual([
      '0-8',
      '8-16',
      '16-24',
      '24-32',
      '32-40',
      '40-48',
      '48-56',
      '56-64',
      '64-72',
      '72-80',
      '80-88',
    ]);
  });

  it('구간형이_아닌_키가_섞여도_정렬이_깨지지_않는다', async () => {
    // given — 레거시 구간 + 수동 등록 키 + 서술 전문 키 혼재
    mock.onGet('/frames/113/meta').reply(200, {
      success: true,
      data: {
        items: [
          { metaSn: 1, metaKey: 'manual-timeseries', metaVal: '수동' },
          { metaSn: 2, metaKey: '10-18', metaVal: '십팔' },
          { metaSn: 3, metaKey: 'vlm.description', metaVal: '서술' },
          { metaSn: 4, metaKey: '8-16', metaVal: '십육' },
        ],
      },
      message: null,
      errorCode: null,
    });

    // when
    const r = await getMeta(113);

    // then — 숫자 구간이 시간순으로 앞서고, 나머지는 사전순으로 뒤에 온다(크래시·유실 없음).
    expect(r.vlmText.split('\n')).toEqual(['십육', '십팔', '수동', '서술']);
    expect(r.items).toHaveLength(4);
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
