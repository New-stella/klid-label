import {
  resolutionDerivativeLabel,
  type ResolutionPreset,
} from '@/features/video/types';

import { type AugmentType } from './types';

/**
 * 작업 목록/이력에서 쓰는 증강 종류 코드 union.
 * - 외부 증강 위탁 3종(WINTER/NIGHT/RAIN)
 * - 해상도 파생 3종(RESL_1080P/RESL_720P/RESL_480P) — SFR-06-03
 * BE 작업목록 항목 DTO 의 augType 필드값과 1:1.
 */
export type AugType = AugmentType | ResolutionPreset;

// WINTER/NIGHT/RAIN 사용자 문구 — JobCard typeLabelMap 과 동일(겨울/야간/비).
// 기술모델명 노출 금지 규칙에 맞춰 코드값이 아닌 한글 라벨만 노출한다.
const AUGMENT_TYPE_LABEL: Record<AugmentType, string> = {
  WINTER: '겨울',
  NIGHT: '야간',
  RAIN: '비',
};

/**
 * 증강 종류 코드 → 사용자 노출 한글 라벨.
 * - WINTER/NIGHT/RAIN → 겨울/야간/비
 * - RESL_* → 해상도 라벨(예 '해상도 720p') — resolutionDerivativeLabel 재사용
 * - null/undefined/미지의 코드 → '증강' 폴백(기술코드 비노출)
 */
export function augTypeLabel(
  augType: AugType | string | null | undefined,
): string {
  if (!augType) return '증강';
  if (augType.startsWith('RESL_')) return resolutionDerivativeLabel(augType);
  return AUGMENT_TYPE_LABEL[augType as AugmentType] ?? '증강';
}
