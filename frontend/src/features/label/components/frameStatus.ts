// 프레임 테두리 4색 상태 — 관리자 매뉴얼 Rev.1.1 / 21 §21.9 색상 체계.
// 순수 셀렉터 (테스트 가능하도록 UI 와 분리).

/**
 * 프레임 상태값. 우선순위(높은 순): CURRENT > INQUIRY > REJECTION > SAVED > NONE.
 * - CURRENT: 현재 보고 있는 프레임 (강조)
 * - INQUIRY: 관리자 확인 요청으로 보낸 프레임 (빨강)
 * - REJECTION: 반려된/반려한 프레임 (주황)
 * - SAVED: 라벨이 저장된 프레임 (연두)
 * - NONE: 라벨/이슈 없음
 */
export const FRAME_STATUS = {
  CURRENT: 'CURRENT',
  INQUIRY: 'INQUIRY',
  REJECTION: 'REJECTION',
  SAVED: 'SAVED',
  NONE: 'NONE',
} as const;
export type FrameStatus = (typeof FRAME_STATUS)[keyof typeof FRAME_STATUS];

export interface FrameStatusInput {
  isCurrent: boolean;
  hasInquiry: boolean;
  hasRejection: boolean;
  hasLabel: boolean;
}

/**
 * 프레임 상태 합성 — 우선순위 현재 > 확인요청 > 반려 > 저장 순으로 단일 상태 결정.
 * 여러 상태가 동시에 참이어도 최상위 하나만 반환한다.
 */
export function resolveFrameStatus(input: FrameStatusInput): FrameStatus {
  if (input.isCurrent) return FRAME_STATUS.CURRENT;
  if (input.hasInquiry) return FRAME_STATUS.INQUIRY;
  if (input.hasRejection) return FRAME_STATUS.REJECTION;
  if (input.hasLabel) return FRAME_STATUS.SAVED;
  return FRAME_STATUS.NONE;
}

/** 상태별 Tailwind 테두리 클래스 (연두/주황/빨강/현재강조). */
export const FRAME_STATUS_BORDER: Record<FrameStatus, string> = {
  CURRENT: 'border-primary-500 scale-105',
  INQUIRY: 'border-red-500',
  REJECTION: 'border-orange-500',
  SAVED: 'border-green-400',
  NONE: 'border-transparent hover:border-gray-500',
};
