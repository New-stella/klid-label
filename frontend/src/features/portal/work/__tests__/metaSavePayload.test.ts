// 포털 메타 저장 payload 조립 — (축, 키) 쌍 식별 + 손대지 않은 자동 계산값 미전송.
//
// @design API-234
// @design API-235
// @design AC-1068
import { describe, expect, it } from 'vitest';

import { axisKeyOf, PORTAL_META_KEYS, splitEditableItems } from '../metaFields';
import { buildMetaSavePayload, hasMetaChanges } from '../metaSavePayload';
import type { PortalMetaItem } from '../types';

function item(over: Partial<PortalMetaItem> & Pick<PortalMetaItem, 'metaKey' | 'scope'>): PortalMetaItem {
  return { metaVl: null, overridden: false, source: 'DERIVED', ...over };
}

/** 개인정보 익명여부 — <b>같은 이름</b>으로 두 축에 있다(이 기능에서 가장 틀리기 쉬운 지점). */
const VIDEO_ANONYMITY = item({
  metaKey: PORTAL_META_KEYS.PRIVACY_ANONYMITY,
  scope: 'video',
  metaVl: 'Y',
});
const FRAME_ANONYMITY = item({
  metaKey: PORTAL_META_KEYS.PRIVACY_ANONYMITY,
  scope: 'frame',
  metaVl: 'N',
});

describe('(축, 메타 키) 쌍 식별', () => {
  /*
   * ★식별자 팩토리 자체의 순수 단언 — 이것이 없으면 아래 케이스들이 <b>스스로 눈이 먼다</b>.
   *   시험과 구현이 같은 팩토리를 쓰므로, 팩토리가 축을 무시하도록 바뀌면 시험 쪽 키도 똑같이
   *   붕괴해 같은 자리를 조회하고 그대로 통과한다(실측으로 확인한 사각이다).
   */
  it('축이_다르면_식별자도_다르다', () => {
    expect(axisKeyOf('video', PORTAL_META_KEYS.PRIVACY_ANONYMITY)).not.toBe(
      axisKeyOf('frame', PORTAL_META_KEYS.PRIVACY_ANONYMITY),
    );
  });

  it('같은_이름의_영상축과_프레임축_항목이_섞이지_않는다', () => {
    const items = [VIDEO_ANONYMITY, FRAME_ANONYMITY];
    // 프레임 축만 고친다.
    const draft = { [axisKeyOf('frame', PORTAL_META_KEYS.PRIVACY_ANONYMITY)]: 'Y' };

    const payload = buildMetaSavePayload(items, draft);

    // 프레임 축 한 건만 나가고, 영상 축은 손대지 않았으므로 나가지 않는다.
    expect(payload).toEqual([
      { metaKey: PORTAL_META_KEYS.PRIVACY_ANONYMITY, metaVl: 'Y', scope: 'frame' },
    ]);
  });

  it('축을_그대로_되돌려_보낸다_영상축_값이_프레임에_매달리지_않는다', () => {
    const draft = { [axisKeyOf('video', PORTAL_META_KEYS.PRIVACY_ANONYMITY)]: 'N' };
    const payload = buildMetaSavePayload([VIDEO_ANONYMITY, FRAME_ANONYMITY], draft);
    expect(payload).toHaveLength(1);
    expect(payload[0].scope).toBe('video');
  });

  it('두_축의_항목이_섹션에서도_각자_선다', () => {
    const { sections } = splitEditableItems([VIDEO_ANONYMITY, FRAME_ANONYMITY]);
    const videoRows = sections.find((s) => s.title === '개인정보(영상)')?.rows ?? [];
    const frameRows = sections.find((s) => s.title === '개인정보(프레임)')?.rows ?? [];
    expect(videoRows.map((r) => r.item.metaVl)).toEqual(['Y']);
    expect(frameRows.map((r) => r.item.metaVl)).toEqual(['N']);
  });
});

describe('자동 계산값 승격 방지', () => {
  it('손대지_않은_자동_계산값은_payload_에_담기지_않는다', () => {
    const items = [
      item({ metaKey: PORTAL_META_KEYS.ENV_TIME_OF_DAY, scope: 'video', metaVl: 'DAY', source: 'DERIVED' }),
      item({ metaKey: PORTAL_META_KEYS.ENV_SEASON, scope: 'video', metaVl: 'WINTER', source: 'DERIVED' }),
    ];
    expect(buildMetaSavePayload(items, {})).toEqual([]);
    expect(hasMetaChanges(items, {})).toBe(false);
  });

  it('원본이_사람이_고른_값이면_손대지_않아도_담긴다', () => {
    const items = [
      item({ metaKey: PORTAL_META_KEYS.ENV_WEATHER, scope: 'video', metaVl: '맑음', source: 'MANUAL' }),
    ];
    expect(buildMetaSavePayload(items, {})).toEqual([
      { metaKey: PORTAL_META_KEYS.ENV_WEATHER, metaVl: '맑음', scope: 'video' },
    ]);
  });

  it('고친_자동_계산값은_담긴다', () => {
    const items = [
      item({ metaKey: PORTAL_META_KEYS.ENV_SEASON, scope: 'video', metaVl: 'WINTER', source: 'DERIVED' }),
    ];
    const draft = { [axisKeyOf('video', PORTAL_META_KEYS.ENV_SEASON)]: 'SPRING' };
    expect(buildMetaSavePayload(items, draft)).toEqual([
      { metaKey: PORTAL_META_KEYS.ENV_SEASON, metaVl: 'SPRING', scope: 'video' },
    ]);
    expect(hasMetaChanges(items, draft)).toBe(true);
  });

  it('빈_값으로_지우면_null_로_보낸다', () => {
    const items = [
      item({ metaKey: PORTAL_META_KEYS.ENV_WEATHER, scope: 'video', metaVl: '맑음', source: 'MANUAL' }),
    ];
    const draft = { [axisKeyOf('video', PORTAL_META_KEYS.ENV_WEATHER)]: '' };
    expect(buildMetaSavePayload(items, draft)).toEqual([
      { metaKey: PORTAL_META_KEYS.ENV_WEATHER, metaVl: null, scope: 'video' },
    ]);
  });
});

describe('편집 가능 목록의 배치', () => {
  it('섹션_정의에_없는_항목은_버리지_않고_시계열_메타로_모은다', () => {
    // 서버가 키를 늘려도 값이 화면에서 증발하면 안 된다(조용한 손실 금지).
    const unknown = item({ metaKey: 'vlm.description', scope: 'video', metaVl: '서술', source: 'STORED' });
    const { timeseries } = splitEditableItems([unknown]);
    expect(timeseries).toEqual([unknown]);
  });

  it('응답에_없는_항목은_만들어_내지_않는다', () => {
    const { sections } = splitEditableItems([]);
    expect(sections.every((s) => s.rows.length === 0)).toBe(true);
  });
});
