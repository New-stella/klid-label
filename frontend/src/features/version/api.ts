// 버전관리 도메인 API — BE: /api/v1/videos/{id}/versions, /versions/{commit}/diff, /versions/{commit}/rollback
//
// 보안 (security.md 정합):
// - 사용자 입력 commit hash는 BE에서 SHA hex(40자) 검증 후 사용 — FE는 단순 전달.
// - axios가 path/query 자동 URL 인코딩 (XSS/CRLF 방어).
// - IDOR 방어 + 롤백 권한(REVIEWER) 검증은 BE 책임.

import { apiClient } from '@/lib/api/client';

import type { LabelDiff, RollbackResponse, Version } from './types';

/**
 * 영상의 라벨 커밋 이력 조회 (최신순).
 * BE: GET /api/v1/videos/{videoId}/versions
 */
export function listVersions(videoId: number): Promise<Version[]> {
  return apiClient.get<Version[]>(`/videos/${videoId}/versions`).then((r) => r.data);
}

/**
 * 두 커밋 간 라벨 diff 조회.
 * BE: GET /api/v1/versions/{commit}/diff?compareWith={otherCommit}
 *
 * compareWith가 없으면 BE는 직전 커밋(부모)과 비교.
 */
export function getDiff(commit: string, compareWith?: string): Promise<LabelDiff[]> {
  return apiClient
    .get<LabelDiff[]>(`/versions/${commit}/diff`, {
      params: compareWith ? { compareWith } : undefined,
    })
    .then((r) => r.data);
}

/**
 * 지정한 커밋의 라벨 상태로 롤백 (현재 HEAD 위에 새 커밋 생성).
 * BE: POST /api/v1/versions/{commit}/rollback
 *
 * 보안: 권한(REVIEWER) 검증 + commit SHA 검증은 BE에서 수행.
 */
export function rollback(commit: string): Promise<RollbackResponse> {
  return apiClient.post<RollbackResponse>(`/versions/${commit}/rollback`).then((r) => r.data);
}
