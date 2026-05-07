// 오토라벨링/시계열 메타 도메인 API.
//
// 보안: srcSn/videoId는 number 타입 (path param). axios 자동 URL 인코딩.
// IDOR/Mass Assignment 방어는 BE 책임 (FE는 분기만).

import { apiClient } from '@/lib/api/client';

import type { AutoLabelSummary, FrameMeta, FrameMetaUpdateRequest } from './types';

export function getAutoLabelSummary(videoId: number): Promise<AutoLabelSummary> {
  return apiClient
    .get<AutoLabelSummary>(`/videos/${videoId}/auto-summary`)
    .then((r) => r.data);
}

export function getMeta(srcSn: number): Promise<FrameMeta> {
  return apiClient.get<FrameMeta>(`/frames/${srcSn}/meta`).then((r) => r.data);
}

export function updateMeta(
  srcSn: number,
  body: FrameMetaUpdateRequest,
): Promise<FrameMeta> {
  return apiClient.put<FrameMeta>(`/frames/${srcSn}/meta`, body).then((r) => r.data);
}
