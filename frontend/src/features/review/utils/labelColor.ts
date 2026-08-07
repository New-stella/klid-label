// SCR-REVIEW-002 — 검수 화면 라벨 표시 색상 어댑터.
//
// ★이 파일은 색상을 **판정하지 않는다**. 라벨 표시 색상의 단일 진실원은 라벨 마스터
//   (LS_LABEL.COLR_VL)이고, 판정기는 `features/label/utils/labelColor.getLabelDisplayColor`
//   **한 곳**이다(확정 정책). 여기서는 검수 응답(BE LabelResponse.Item)을 그 판정기의 입력
//   형태로 옮기기만 한다 — 판정 순서(color → 마스터 → trackId → source)를 복제하지 않는다.
//
// ⚠ 구 `FIXED_COLORS`(라벨명 하드코딩 6종 + 라벨명 해시 폴백)는 **폐기**했다. 마스터에서 색을
//   바꿔도 반영되지 않는 두 번째 진실원이었고, 라벨링 쪽 `features/label/labelColors.ts`의
//   `LABEL_CLASS_DEFS` 가 정확히 같은 이유로 파일째 삭제된 전례가 있다. 되살리지 말 것.
//
// 입력은 `LabelItem` 그대로다. 한때 `labelId`/`color`/`trackId` 를 선언하지 않는 과소 타입을
// 피하려고 별도 구조 타입(`ReviewColorLabel`)으로 우회했으나, `review/types.ts(LabelItem)` 이
// 세 필드를 선언하면서 그 우회는 **불필요해져 제거**했다. BE `FrameDetailResponse.labels` 는
// 라벨링과 같은 `LabelResponse.Item` 이며, 검수 응답 경로(`ReviewService.listFrames`)는
// `LabelResponse.Item.from(entity, null, null, ...)` 로 빌드해 **`color` 는 항상 null,
// `labelId`/`trackId` 는 채워진다** → 마스터 lookup(2순위)이 실동작 경로다.
//
// 보안: 반환 색상 문자열은 Konva stroke / inline style 로만 흐르며 DOM innerHTML 삽입 경로가 없다.

import type { LabelMaster } from '@/features/label/api/labelMaster';
import { getLabelDisplayColor } from '@/features/label/utils/labelColor';
import { trackIdToColor } from '@/features/label/utils/trackColor';
import type { Label } from '@/features/label/types';

import type { LabelItem } from '../types';

/**
 * 검수 라벨 → 공용 판정기(`getLabelDisplayColor`)가 읽는 형태로 변환.
 *
 * 판정기가 실제로 읽는 필드는 `color`/`labelId`/`classId`/`trackId`/`source` 뿐이며,
 * 나머지는 `Label` 타입을 만족시키기 위한 자리값이다(색 판정에 관여하지 않는다).
 * - `classId: 0` — 판정기의 classId 폴백은 `> 0` 일 때만 동작하므로 0 은 "미사용" 을 뜻한다.
 * - `source: 'MANUAL'` — 마스터·트랙이 모두 없을 때의 최종 폴백 색상 축. 검수 화면은 편집이
 *   없어 출처별 색 구분이 의미가 없고, 회색이 아닌 색으로 떨어져 "미등록 분류가 회색으로
 *   죽는" 구 하드코딩 표의 결함이 재현되지 않는다.
 */
export function toColorLabel(item: LabelItem): Label {
  return {
    id: '',
    frameNo: 0,
    classId: 0,
    className: item.label,
    labelId: item.labelId ?? null,
    color: item.color ?? null,
    trackId: item.trackId ?? null,
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 0, top: 0, right: 0, bottom: 0 },
  };
}

/**
 * 검수 라벨 1건의 표시 색상 — 판정은 전적으로 `getLabelDisplayColor` 에 위임한다.
 *
 * @param item         검수 라벨(라벨명 + 마스터/트랙 연결값)
 * @param labelMasters 라벨 마스터 목록(`useLabelMasters`). undefined 면 마스터 lookup 생략.
 * @param options      `useTrackFallback:false` 면 트랙 해시색 유입을 막는다(분류 축 전용).
 */
export function reviewLabelColor(
  item: LabelItem,
  labelMasters: ReadonlyArray<LabelMaster> | undefined,
  options?: { useTrackFallback?: boolean },
): string {
  return getLabelDisplayColor(toColorLabel(item), labelMasters, options);
}

/**
 * 개별 항목 막대 색상 — **트랙 시각화 축**(분류 축과 별개다).
 *
 * 라벨링 객체 트리가 항목 막대에 `trackIdToColor` 를 그대로 쓰는 것과 같은 규칙이며,
 * 두 축을 통일하지 않는 것이 확정 정책이다. 얇은 래퍼로 남겨 두는 이유는 두 축(분류/트랙)의
 * 호출 지점을 한 파일에서 나란히 읽히게 하기 위해서다.
 */
export function reviewTrackBarColor(item: LabelItem): string {
  return trackIdToColor(item.trackId);
}

/**
 * fill 색상용 — 이미 판정된 stroke 색상에 알파를 더한다(라벨명으로 색을 다시 구하지 않는다).
 * HEX 는 8자리 HEX 로, `hsl(...)` 은 `hsla(...)` 로 변환한다. 그 외 형식은 원본을 그대로 돌려준다.
 */
export function withAlpha(color: string, alpha = 0.15): string {
  const a = Math.max(0, Math.min(1, alpha));
  if (color.startsWith('#') && color.length === 7) {
    const hex = Math.round(a * 255)
      .toString(16)
      .padStart(2, '0');
    return `${color}${hex}`;
  }
  if (color.startsWith('hsl(')) {
    return `hsla(${color.slice(4, -1)}, ${a})`;
  }
  return color;
}
