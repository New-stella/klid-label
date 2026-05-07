// 영상 도메인 API — BE: /api/v1/videos, /videos/{id}, /api/v1/batch/status

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type { BatchStatus, Video, VideoDetail, VideoListParams } from './types';

/**
 * 보안: axios가 자동 URL 인코딩 (XSS/Injection 방지).
 * 사용자 입력은 params로만 전달 — 문자열 직접 연결 금지.
 */
export function listVideos(params: VideoListParams) {
  return apiClient
    .get<PageResponse<Video>>('/videos', { params })
    .then((r) => r.data);
}

export function getVideo(id: number) {
  return apiClient.get<VideoDetail>(`/videos/${id}`).then((r) => r.data);
}

export function getBatchStatus() {
  return apiClient.get<BatchStatus>('/batch/status').then((r) => r.data);
}
