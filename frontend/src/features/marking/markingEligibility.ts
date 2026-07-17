import { isMarkingBlocked } from '@/features/video/types';

// 영상 배치 단계 상태 문자열 (LS_DATA_RAW.DATA_STTS_CD). 매직스트링 대신 로컬 상수로 묶는다.
const VIDEO_STATUS = {
  MARKING_READY: 'MARKING_READY',
  PROCESSING: 'PROCESSING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
} as const;

// 마킹이 이미 진행/종료된 것으로 간주하는 상태 집합.
const MARKING_DONE_STATUSES: readonly string[] = [
  VIDEO_STATUS.PROCESSING,
  VIDEO_STATUS.COMPLETED,
  VIDEO_STATUS.FAILED,
];

/**
 * 마킹 진입 가능 여부.
 *
 * true ⟺ 배치 단계가 MARKING_READY 이고 비식별이 확정적으로 미완료가 아닐 때.
 * 비식별 판정은 {@link isMarkingBlocked} 를 재사용한다(중복 구현 금지).
 */
export function canMark(v: {
  status?: string;
  deIdntfYn?: string;
  deidentStatus?: string;
}): boolean {
  return v.status === VIDEO_STATUS.MARKING_READY && !isMarkingBlocked(v);
}

/**
 * 마킹(배치)이 이미 진행 중이거나 종료된 상태인지 여부.
 * PROCESSING / COMPLETED / FAILED → true, 그 외(MARKING_READY/PENDING/미정) → false.
 */
export function isMarkingDone(v: { status?: string }): boolean {
  return v.status != null && MARKING_DONE_STATUSES.includes(v.status);
}
