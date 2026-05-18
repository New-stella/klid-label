// 버전관리 도메인 API — BE: /api/v1/frames/{srcSn}/versions, /versions/{commit}/diff, /versions/{commit}/rollback
//
// 경로 의미 (2026-05-18 정리):
// - srcSn 은 LS_DATA_SRC.SRC_SN (프레임 단위 PK). 영상(LS_DATA_RAW.RAW_SN)이 아니다.
// - 기존 /v1/videos/{srcSn}/versions 는 BE deprecated alias 로 유지되지만 FE 는 정식 경로 /frames/{srcSn}/versions 사용.
//
// 보안 (security.md 정합):
// - 사용자 입력 commit hash는 BE에서 SHA hex(40자) 검증 후 사용 — FE는 단순 전달.
// - axios가 path/query 자동 URL 인코딩 (XSS/CRLF 방어).
// - IDOR 방어 + 롤백 권한(REVIEWER) 검증은 BE 책임.

import { apiClient } from '@/lib/api/client';

import type { LabelDiff, RollbackResponse, Version } from './types';

/**
 * 프레임(srcSn)의 라벨 커밋 이력 조회 (최신순).
 * BE: GET /api/v1/frames/{srcSn}/versions
 *
 * @param srcSn LS_DATA_SRC.SRC_SN — 프레임 단위 PK (LabelingPage 에서는 data.srcSn 그대로 전달)
 */
export function listVersions(srcSn: number): Promise<Version[]> {
  return apiClient.get<Version[]>(`/frames/${srcSn}/versions`).then((r) => r.data);
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
export function rollback(commit: string, srcSn: number): Promise<RollbackResponse> {
  return apiClient
    .post<RollbackResponse>(`/versions/${commit}/rollback`, { srcSn })
    .then((r) => r.data);
}
