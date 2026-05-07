// 포털 채널 API — BE: /api/v1/portal/*
// 보안: axios가 자동 URL 인코딩. IDOR 방어는 BE 책임 (PORTAL_USER 본인 데이터만 노출).

import { apiClient } from '@/lib/api/client';

import type { PortalAutolabelResponse, PortalUpload } from './types';

/**
 * 본인이 업로드한 영상 목록 조회.
 * BE: GET /api/v1/portal/uploads — 인증 사용자 본인 데이터만 반환.
 */
export function listMyUploads(): Promise<PortalUpload[]> {
  return apiClient.get<PortalUpload[]>('/portal/uploads').then((r) => r.data);
}

/**
 * 오토라벨링 요청 (체험형 — YOLO+SAM2).
 * BE: POST /api/v1/portal/autolabel — 본인 업로드 srcSn에 대해서만 허용.
 */
export function requestAutolabel(srcSn: number): Promise<PortalAutolabelResponse> {
  return apiClient
    .post<PortalAutolabelResponse>('/portal/autolabel', { srcSn })
    .then((r) => r.data);
}

/**
 * 포털 라벨링 결과 저장 (간편 라벨링 — 버전관리 미제공).
 * BE: PUT /api/v1/portal/labels/{srcSn}.
 */
export function savePortalLabels(srcSn: number, labels: unknown[]): Promise<{ savedCount: number }> {
  return apiClient
    .put<{ savedCount: number }>(`/portal/labels/${srcSn}`, { labels })
    .then((r) => r.data);
}
