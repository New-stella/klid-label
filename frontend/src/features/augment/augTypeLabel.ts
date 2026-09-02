import {
  resolutionDerivativeLabel,
  type ResolutionPreset,
} from '@/features/video/types';

import { type AugmentTypeDisplay } from './types';

/**
 * 작업 목록/이력에서 쓰는 증강 종류 코드 union — **표시 축**이다.
 * - 신규 증강 위탁 단일값(AUGMENT)
 * - 그랜드퍼더링된 구 3종(WINTER/NIGHT/RAIN) — 백필하지 않아 기존 파생본에 그대로 남아 있다
 * - 해상도 파생 3종(RESL_1080P/RESL_720P/RESL_480P) — SFR-06-03
 * BE 작업목록 항목 DTO 의 augType 필드값과 1:1.
 */
export type AugType = AugmentTypeDisplay | ResolutionPreset;

/**
 * 증강 종류 코드 → 사용자 노출 한글 문구 — JobCard 배지와 같은 표를 쓴다.
 *
 * ⚠ 구 3종을 지우지 말 것. 신규 요청은 `AUGMENT` 하나만 만들지만 BE 가 기존 행을 백필하지
 * 않았으므로(ADR-059) 이력·결과 화면은 계속 이 값들을 만난다. 지우면 그 화면에서 배지가
 * '증강' 폴백으로 뭉개져 어떤 파생인지 구분할 수 없게 된다.
 */
const AUGMENT_TYPE_LABEL: Record<AugmentTypeDisplay, string> = {
  AUGMENT: '증강 AI',
  WINTER: '겨울',
  NIGHT: '야간',
  RAIN: '비',
};

/**
 * 증강 종류 코드 → 사용자 노출 한글 라벨.
 * - AUGMENT → 증강 AI
 * - WINTER/NIGHT/RAIN → 겨울/야간/비 (그랜드퍼더링)
 * - RESL_* → 해상도 라벨(예 '해상도 720p') — resolutionDerivativeLabel 재사용
 * - null/undefined/미지의 코드 → '증강' 폴백(기술코드 비노출)
 */
export function augTypeLabel(
  augType: AugType | string | null | undefined,
): string {
  if (!augType) return '증강';
  if (augType.startsWith('RESL_')) return resolutionDerivativeLabel(augType);
  return AUGMENT_TYPE_LABEL[augType as AugmentTypeDisplay] ?? '증강';
}
