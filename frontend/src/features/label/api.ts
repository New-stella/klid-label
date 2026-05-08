// 라벨 도메인 API — BE: /api/v1/frames/{srcSn}/labels, /commit
//
// 보안: 사용자 입력은 path/body 파라미터로만 전달 (axios 자동 URL 인코딩, XSS 방지).
// IDOR/Mass Assignment 방어는 BE 책임.

import { apiClient } from '@/lib/api/client';

import type { Label, LabelsResponse } from './types';

export interface CommitResponse {
  commitSha: string;
  committedAt: string;
}

/**
 * 프레임의 라벨 목록 조회.
 */
export function getLabels(srcSn: number): Promise<LabelsResponse> {
  return apiClient
    .get<LabelsResponse | { items: Label[] }>(`/frames/${srcSn}/labels`)
    .then((r) => {
      const d = r.data as LabelsResponse & { items?: Label[] };
      return {
        frameNo: d.frameNo ?? 0,
        srcSn,
        labels: Array.isArray(d.labels) ? d.labels : Array.isArray(d.items) ? d.items : [],
      };
    });
}

/**
 * 프레임 라벨 일괄 저장 (전체 교체).
 * BE: PUT /frames/{srcSn}/labels
 */
export function putLabels(srcSn: number, labels: Label[]): Promise<LabelsResponse> {
  return apiClient
    .put<LabelsResponse>(`/frames/${srcSn}/labels`, { labels })
    .then((r) => r.data);
}

/**
 * Gitea 커밋 트리거 (저장 후 버전관리 반영).
 * BE: POST /frames/{srcSn}/commit
 */
export function commitLabels(srcSn: number, message?: string): Promise<CommitResponse> {
  return apiClient
    .post<CommitResponse>(`/frames/${srcSn}/commit`, { message: message ?? null })
    .then((r) => r.data);
}

export interface Sam2TrackRequest {
  /** seed BBox: [left, top, right, bottom] (image px) */
  bbox: [number, number, number, number];
  classId: number;
  /** 다음 N개 프레임까지 자동 추적 (BE 정책상 상한 적용) */
  targetFrameCount: number;
  /** 트랙 ID (이어붙일 기존 트랙 — 없으면 BE가 새로 발급) */
  trackId?: number;
}

export interface Sam2TrackPropagatedFrame {
  frameNo: number;
  bbox: [number, number, number, number];
  confidence?: number;
}

export interface Sam2TrackResponse {
  trackId: number;
  propagatedFrames: Sam2TrackPropagatedFrame[];
}

/**
 * SAM2 자동 추적 요청.
 * BE: POST /frames/{srcSn}/sam2-track
 *
 * 보안: srcSn/bbox/classId 입력 검증 + IDOR 방어는 BE 책임.
 */
export function requestSam2Track(
  srcSn: number,
  payload: Sam2TrackRequest,
): Promise<Sam2TrackResponse> {
  return apiClient
    .post<Sam2TrackResponse>(`/frames/${srcSn}/sam2-track`, payload)
    .then((r) => r.data);
}
