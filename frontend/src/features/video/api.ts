// 영상 도메인 API — BE: /api/v1/videos, /videos/{id}, /api/v1/batch/status

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type { BatchStatus, FrameLabels, Video, VideoDetail, VideoListParams } from './types';

/**
 * 보안: axios가 자동 URL 인코딩 (XSS/Injection 방지).
 * 사용자 입력은 params로만 전달 — 문자열 직접 연결 금지.
 *
 * BE 응답이 신/구 필드 혼재 가능성을 가정해 normalize 단계를 둔다:
 *  - id      ← rawSn fallback
 *  - cctvName ← vmsCctvId fallback
 *  - eventTypeCd ← evntTypeCd fallback (camelCase 정정 이전 응답 호환)
 *  - capturedAt ← regDt fallback
 *  - frameCount ← 0 fallback
 *  - status ← dataSttsCd fallback ('PENDING' 최종 fallback)
 */
type RawVideo = Partial<Video> & {
  rawSn?: number;
  vmsCctvId?: string;
  evntTypeCd?: string;
  dataSttsCd?: string;
  regDt?: string;
  prvcTypeCd?: string;
};

function normalizeVideo(v: RawVideo): Video {
  return {
    id: (v.id ?? v.rawSn) as number,
    cctvName: (v.cctvName ?? v.vmsCctvId ?? '') as string,
    vmsClipId: v.vmsClipId ?? '',
    eventName: v.eventName,
    eventTypeCd: v.eventTypeCd ?? v.evntTypeCd,
    localGov: v.localGov,
    frameCount: v.frameCount ?? 0,
    status: (v.status ?? v.dataSttsCd ?? 'PENDING') as Video['status'],
    capturedAt: (v.capturedAt ?? v.regDt ?? '') as string,
    thumbnailUrl: v.thumbnailUrl,
    privacyTypeCd: (v.privacyTypeCd ?? v.prvcTypeCd) as string | undefined,
    durationSec: v.durationSec,
    updatedAt: v.updatedAt ?? null,
    reviewCompletedAt: v.reviewCompletedAt ?? null,
  };
}

export function listVideos(params: VideoListParams) {
  return apiClient
    .get<PageResponse<RawVideo>>('/videos', { params })
    .then((r) => ({
      ...r.data,
      content: (r.data.content ?? []).map(normalizeVideo),
    }));
}

export function getVideo(id: number) {
  return apiClient
    .get<RawVideo & Partial<VideoDetail> & { durationSec?: number; regDt?: string; updDt?: string }>(
      `/videos/${id}`,
    )
    .then((r) => {
      const base = normalizeVideo(r.data);
      const d = r.data;
      return {
        ...base,
        duration: d.duration ?? d.durationSec ?? 0,
        fileSizeMb: d.fileSizeMb ?? 0,
        resolution: d.resolution ?? '',
        framePreviews: (d.framePreviews ?? []).map((fp) => ({
          srcSn: fp.srcSn,
          frameNo: fp.frameNo,
          thumbnailUrl: fp.thumbnailUrl,
        })),
        stages: d.stages ?? [],
        createdAt:
          d.createdAt ??
          ((d as Record<string, unknown>)['regDt'] as string | undefined),
        updatedAt:
          d.updatedAt ??
          ((d as Record<string, unknown>)['updDt'] as string | undefined),
      } as VideoDetail;
    });
}

export function getBatchStatus() {
  return apiClient.get<BatchStatus>('/batch/status').then((r) => r.data);
}

export function getVideoLabels(videoId: number | string) {
  return apiClient
    .get<FrameLabels>(`/videos/${videoId}/labels/auto`)
    .then((r) => r.data);
}
