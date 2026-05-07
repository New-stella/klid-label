// 배경영상 생성 요청 API — BE: /api/v1/generate/background.
//
// 보안:
// - axios가 path/body 파라미터 자동 URL 인코딩 (XSS/Injection 방어).
// - genType allowlist (WILDFIRE/FLOOD) — 라디오 강제 + BE @Valid + DB enum.
// - REVIEWER 역할 검증은 라우터 RoleGuard + BE @PreAuthorize.
// - srcSn/videoId는 number 타입으로 IDOR/Mass Assignment 1차 차단 — BE도 동일 검증.

import { apiClient } from '@/lib/api/client';

import type {
  BackgroundGenerateJob,
  RequestBackgroundGenerateRequest,
  RequestBackgroundGenerateResponse,
} from './types';

/**
 * 배경영상 생성 요청 (외부 생성형 AI 시스템에 전달 트리거).
 * BE: POST /api/v1/generate/background
 */
export function requestBackgroundGenerate(
  body: RequestBackgroundGenerateRequest,
): Promise<RequestBackgroundGenerateResponse> {
  return apiClient
    .post<RequestBackgroundGenerateResponse>('/generate/background', body)
    .then((r) => r.data);
}

/**
 * 배경영상 요청 결과 단건 조회 (SCR-GEN-002 단일 카드).
 * BE: GET /api/v1/generate/background/{jobId}
 */
export function getBackgroundGenerateJob(
  jobId: number,
): Promise<BackgroundGenerateJob> {
  return apiClient
    .get<BackgroundGenerateJob>(`/generate/background/${jobId}`)
    .then((r) => r.data);
}
