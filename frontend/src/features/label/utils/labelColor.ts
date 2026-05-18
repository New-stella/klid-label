// 라벨 캔버스 표시 색상 결정 유틸 (Phase 3 — LS_LABEL.color 매핑).
//
// 우선순위:
//   1) label.color (BE LabelResponse.Item.color → LS_LABEL.color, '#RRGGBB')
//   2) labelMasters[label.labelId].color (BE 가 enrichment 안 한 응답 대비)
//   3) trackId 기반 결정적 HSL 색상 (트랙 시각화)
//   4) source 기반 fallback (AUTO_YOLO / AUTO_SAM2 / MANUAL)
//
// 보안: 색상 문자열은 BE 에서 '#RRGGBB' 정규화되어 들어오며, 본 함수는 그 값을
//       Konva stroke prop 으로만 전달한다 — DOM innerHTML 삽입 경로 없음.

import type { LabelMaster } from '../api/labelMaster';
import type { Label, LabelSource } from '../types';
import { trackIdToColor } from './trackColor';

/** source 별 fallback 색상 (#RRGGBB) — LabelsLayer 기존 동작 유지. */
const SOURCE_FALLBACK_COLOR: Record<LabelSource, string> = {
  AUTO_YOLO: '#5B8FF9',
  AUTO_SAM2: '#9C6CF9',
  MANUAL: '#26A69A',
};

/** HEX 색상 '#RRGGBB' 형식 검증 (대소문자 허용). */
function isHexColor(value: string | null | undefined): value is string {
  return typeof value === 'string' && /^#[0-9A-Fa-f]{6}$/.test(value);
}

/**
 * 라벨 캔버스 표시 색상 결정.
 *
 * @param label 라벨 (color/labelId/trackId/source 사용)
 * @param labelMasters 라벨 마스터 목록 (undefined 면 lookup 생략)
 * @param options.useTrackFallback true 면 source fallback 전에 trackId 해시 색상 사용 (기본 true).
 *                                  false 면 trackId 단계를 건너뛰고 곧바로 source fallback (테스트/특수 모드용)
 */
export function getLabelDisplayColor(
  label: Label,
  labelMasters: ReadonlyArray<LabelMaster> | undefined,
  options?: { useTrackFallback?: boolean },
): string {
  // 1) 라벨 자체에 color 가 enrichment 되어 있으면 우선
  if (isHexColor(label.color)) {
    return label.color;
  }

  // 2) labelMasters lookup (label.labelId 또는 fallback 으로 classId)
  const lookupId =
    label.labelId !== null && label.labelId !== undefined && Number.isFinite(label.labelId)
      ? label.labelId
      : Number.isFinite(label.classId) && label.classId > 0
        ? label.classId
        : null;
  if (lookupId !== null && labelMasters && labelMasters.length > 0) {
    const matched = labelMasters.find((m) => m.labelId === lookupId);
    if (matched && isHexColor(matched.color)) {
      return matched.color;
    }
  }

  // 3) trackId 기반 결정적 색상 (옵션)
  const useTrackFallback = options?.useTrackFallback ?? true;
  if (useTrackFallback && label.trackId) {
    return trackIdToColor(label.trackId);
  }

  // 4) source fallback
  return SOURCE_FALLBACK_COLOR[label.source] ?? '#94A3B8';
}
