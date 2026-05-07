// 비식별화 결과 API.
//
// 보안: 사용자 입력은 params/path로만 전달 (axios 자동 인코딩, XSS/Injection 방지).
// IDOR 방어는 BE 책임. 이미지 URL은 BE 응답값만 사용 (사용자 입력 금지).

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type { DeidentDetail, DeidentListParams, DeidentListRow } from './types';

export function listDeidentResults(params: DeidentListParams) {
  return apiClient
    .get<PageResponse<DeidentListRow>>('/deident', { params })
    .then((r) => r.data);
}

export function getDeidentDetail(videoId: number): Promise<DeidentDetail> {
  return apiClient.get<DeidentDetail>(`/deident/${videoId}`).then((r) => r.data);
}

export function reprocessDeident(videoId: number): Promise<void> {
  return apiClient.post(`/deident/${videoId}/reprocess`).then(() => undefined);
}
