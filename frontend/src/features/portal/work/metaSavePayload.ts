// 포털 메타 저장 payload 조립 — 순수 함수(무상태). 컴포넌트 import 없음.
//
// <h3>★★ 사용자가 직접 고치지 않은 항목은 담지 않는다</h3>
// 이 창구가 담는 축 가운데 촬영환경·프레임 설명·개인정보 판정 셋은 원장의 <b>컬럼</b>에서 오며,
// 그 축의 유효값은 «수동 저장값이 있으면 그 값, 없으면 자동으로 계산한 값»이다. 화면이 그 자동
// 계산값을 그대로 되돌려 보내면 오버레이 행이 생겨 <b>사람의 판정으로 승격</b>된다.
//
// 판정은 이 채널이 새로 만들지 않고 {@link isUserDeterminedMetaValue} 를 그대로 쓴다 — 내부 라벨링
// 화면의 촬영환경·개인정보 패널이 이미 같은 규율을 지키고 있고, 사본이 늘면 한쪽만 고쳐도 화면은
// 그럴듯하게 보인다.
//
// <h3>★이것이 유일한 방어가 아니다 — 그렇다고 「지키면 좋은 것」이 아니다</h3>
// 창구도 같은 것을 <b>독립으로</b> 막는다(받은 값이 원본의 현재 유효값과 같으면 오버레이를 만들지
// 않고, 이미 있으면 지운다). 그래서 이 계약을 어겨도 저장소가 오염되지는 않는다. 그럼에도 화면이
// 지키는 이유는 ①보내지 않으면 애초에 판정할 것이 없고 ②무변경 항목까지 실어 보내면 사용자가
// 손대지 않은 항목이 저장 결과에 섞여 「무엇을 고쳤는지」가 흐려지기 때문이다.

// @design API-235 @design AC-1068

import { isUserDeterminedMetaValue } from '@/features/label/utils/metaPromotion';

import { axisKeyOfItem } from './metaFields';
import type { PortalMetaItem, PortalMetaSaveItem } from './types';

/** 화면 값(빈 문자열 = 값 없음) → 전송값(`null` = 값 없음). 서버도 공백을 없음으로 정규화한다. */
export function toWireValue(value: string): string | null {
  return value.trim() === '' ? null : value;
}

/** 응답 값 → 화면 값(빈 문자열 = 값 없음). */
export function toFormValue(metaVl: string | null | undefined): string {
  return metaVl ?? '';
}

/**
 * 저장 요청에 실을 원소를 고른다.
 *
 * @param items 화면이 받아 그린 편집 가능 원소(원본 값·출처의 진실원)
 * @param draft (축, 키) 쌍 → 화면의 현재 값. 손대지 않은 항목은 여기 없을 수 있다.
 */
export function buildMetaSavePayload(
  items: PortalMetaItem[],
  draft: Record<string, string>,
): PortalMetaSaveItem[] {
  const payload: PortalMetaSaveItem[] = [];
  for (const item of items) {
    const axis = axisKeyOfItem(item);
    const original = toFormValue(item.metaVl);
    const current = draft[axis] ?? original;
    if (!isUserDeterminedMetaValue(current, original, item.source)) continue;
    // ★축을 그대로 되돌려 보낸다 — 영상 축 원소를 프레임 축으로 보내면 없는 프레임을 가리킨다.
    payload.push({ metaKey: item.metaKey, metaVl: toWireValue(current), scope: item.scope });
  }
  return payload;
}

/** 화면 값 가운데 원본과 다른 것이 하나라도 있는가(저장 버튼 활성 판정). */
export function hasMetaChanges(items: PortalMetaItem[], draft: Record<string, string>): boolean {
  return items.some((item) => {
    const original = toFormValue(item.metaVl);
    return (draft[axisKeyOfItem(item)] ?? original) !== original;
  });
}
