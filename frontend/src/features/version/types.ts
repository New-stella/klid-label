// 버전 도메인 타입 (BE OpenAPI alias — Phase 8 BE 스키마와 동기화)
//
// Gitea 커밋을 영상 단위로 매핑한 버전 이력 + diff(라벨 단위 변경) 표현.

import type { Shape } from '@/features/label/types';

/**
 * 영상 1건의 라벨 변경 이력 항목.
 * - commitSha: Gitea 전체 SHA-1 (40 hex)
 * - shortHash: 표시용 짧은 SHA (보통 7~8자)
 * - isCurrent: 현재 HEAD 여부 — 목록 상단에 "현재" 뱃지 노출
 *
 * 보안: commitSha는 BE에서 SHA hex 검증 후 클라이언트로 내려옴 — FE는 단순 전달만.
 */
export interface Version {
  commitSha: string;
  shortHash: string;
  authorName: string;
  message: string;
  committedAt: string; // ISO-8601
  isCurrent: boolean;
}

export const DiffType = {
  ADDED: 'ADDED',
  MODIFIED: 'MODIFIED',
  REMOVED: 'REMOVED',
} as const;
export type DiffType = (typeof DiffType)[keyof typeof DiffType];

/**
 * 두 버전 간 라벨 단위 차이.
 * - ADDED: after만 존재 (새로 생성된 라벨)
 * - MODIFIED: before/after 둘 다 (모양/위치 변경)
 * - REMOVED: before만 존재 (삭제된 라벨)
 */
export interface LabelDiff {
  type: DiffType;
  frameId: number;
  objectId: string;
  before?: Shape;
  after?: Shape;
}

export interface RollbackResponse {
  newCommitSha: string;
  rolledBackFrom: string;
}
