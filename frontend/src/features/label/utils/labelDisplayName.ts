// 라벨명 표시 변환 — **한글 우선 표시의 단일 공용 함수** (2026-08-03 사용자 확정).
//
// 라벨명이 렌더되는 모든 지점(라벨 선택 모달 / 우측 '객체' 목록 / 객체 속성 / AI 탐지 팝업 /
// 포털 업로드 라벨링)이 이 함수 하나만 호출한다. 표시 규칙이 지점마다 흩어지면 같은 라벨이
// 화면마다 다르게 보인다 — 그래서 함수는 하나다.
//
// 규칙:
//   1) 라벨명에 한글(비-ASCII)이 포함되면 → 그대로 사용
//   2) 아니면 COCO 매핑(dtctTypeCd, 없으면 라벨명 자체)을 키로 한글 사전 조회 → 있으면 한글
//      2-b) COCO 사전에 없으면 레거시 className 코드 사전(LABEL_CLASS_DEFS: PERSON/VEHICLE 등)
//   3) 어느 사전에도 없으면 원문 그대로
//
// ★ **표시 전용이다.** 저장/전송되는 라벨 식별자·이름(LS_DATA_LBL 로 가는 className,
//   이벤트 어노테이션 obj_label, SAM2 추적 요청 label 등)에는 절대 적용하지 않는다.
//   회귀 가드: `__tests__/labelDisplayNameNoPayloadLeak.test.tsx`.
//
// 보안: 사전 조회는 Object.prototype 상속 속성(constructor/__proto__/toString)이 적중으로
//       오인되지 않도록 own-property + 문자열 타입을 함께 확인한다.

import { COCO_LABEL_KO } from '../constants/cocoClasses';
import { LABEL_CLASS_DEFS } from '../labelColors';

/** 값이 비어있을 때 표시할 문자열. */
const EMPTY_DISPLAY = '-';

/** ASCII 범위를 벗어난 문자(=한글 등) 포함 여부. 이미 한글인 라벨명은 사전을 타지 않는다. */
function hasNonAscii(value: string): boolean {
  for (let i = 0; i < value.length; i += 1) {
    if (value.charCodeAt(i) > 127) return true;
  }
  return false;
}

/** 프로토타입 상속 키를 적중으로 오인하지 않는 안전 사전 조회. */
function lookupKo(dict: Readonly<Record<string, unknown>>, key: string): string | null {
  if (!Object.prototype.hasOwnProperty.call(dict, key)) return null;
  const hit = dict[key];
  return typeof hit === 'string' && hit.length > 0 ? hit : null;
}

/**
 * 라벨 표시명 결정 (한글 우선).
 *
 * @param rawName 라벨 마스터 이름 또는 라벨의 className(원문)
 * @param dtctTypeCd AI(COCO) 검출 클래스 매핑값 — 있으면 사전 조회 키로 우선 사용
 */
export function resolveLabelDisplayName(
  rawName: string | null | undefined,
  dtctTypeCd?: string | null,
): string {
  const name = (rawName ?? '').trim();
  if (name.length === 0) return EMPTY_DISPLAY;

  // 1) 이미 한글(비-ASCII) → 그대로
  if (hasNonAscii(name)) return name;

  // 2) COCO 매핑 우선, 없으면 라벨명 자체를 키로
  const coco = lookupKo(COCO_LABEL_KO, (dtctTypeCd ?? name).trim().toLowerCase());
  if (coco !== null) return coco;

  // 2-b) 레거시 className 코드 사전(하위호환 — 우측 객체 목록이 쓰던 표시명 유지)
  const legacyKey = name.toUpperCase();
  if (Object.prototype.hasOwnProperty.call(LABEL_CLASS_DEFS, legacyKey)) {
    const legacy = LABEL_CLASS_DEFS[legacyKey];
    if (legacy) return legacy.name;
  }

  // 3) 원문 그대로
  return name;
}
