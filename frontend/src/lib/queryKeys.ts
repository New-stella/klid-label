// Query Key Factory — 도메인별 골격 (Phase 1+에서 도메인별로 확장)

import type { NoticeListParams } from '@/features/notice/types';

export const USER_KEYS = {
  all: ['users'] as const,
  me: () => [...USER_KEYS.all, 'me'] as const,
  list: (params: Record<string, unknown>) => [...USER_KEYS.all, 'list', params] as const,
  detail: (id: number) => [...USER_KEYS.all, 'detail', id] as const,
};

export const ASSIGNMENT_KEYS = {
  all: ['assignments'] as const,
  list: (params: Record<string, unknown>) => [...ASSIGNMENT_KEYS.all, 'list', params] as const,
  history: (id: number) => [...ASSIGNMENT_KEYS.all, 'history', id] as const,
};

/**
 * SCR-TASK-001 REVIEWER 통합 작업 목록 — BE /v1/tasks/board.
 * 페이지/상태별로 키를 분리하여 React Query 가 캐시를 분리 관리한다.
 */
export const TASK_BOARD_KEYS = {
  all: ['taskBoard'] as const,
  list: (params: Record<string, unknown>) => [...TASK_BOARD_KEYS.all, 'list', params] as const,
};

export const VIDEO_KEYS = {
  all: ['videos'] as const,
  list: (params: Record<string, unknown>) => [...VIDEO_KEYS.all, 'list', params] as const,
  detail: (id: number) => [...VIDEO_KEYS.all, 'detail', id] as const,
  streamUrl: (id: number) => [...VIDEO_KEYS.all, 'stream-url', id] as const,
};

export const LABEL_KEYS = {
  all: ['labels'] as const,
  byVideo: (videoId: number) => [...LABEL_KEYS.all, 'video', videoId] as const,
  byFrame: (videoId: number, frameNo: number) =>
    [...LABEL_KEYS.all, 'video', videoId, 'frame', frameNo] as const,
  /** 프레임(srcSn) 라벨 변경 이력 — 전체 페이지 prefix (저장 후 일괄 invalidate 용). */
  historyByFrame: (srcSn: number) => [...LABEL_KEYS.all, 'history', srcSn] as const,
  /** 프레임(srcSn) 라벨 변경 이력 — 페이지별 캐시 키. */
  history: (srcSn: number, page: number) =>
    [...LABEL_KEYS.historyByFrame(srcSn), page] as const,
};

export const REVIEW_KEYS = {
  all: ['reviews'] as const,
  pending: (params: Record<string, unknown>) => [...REVIEW_KEYS.all, 'pending', params] as const,
  detail: (id: number) => [...REVIEW_KEYS.all, 'detail', id] as const,
  frames: (videoId: number) => [...REVIEW_KEYS.all, 'frames', videoId] as const,
  issueThreads: (rawSn: number) =>
    [...REVIEW_KEYS.all, 'issueThreads', rawSn] as const,
};

export const VERSION_KEYS = {
  all: ['versions'] as const,
  history: (videoId: number) => [...VERSION_KEYS.all, 'history', videoId] as const,
  diff: (videoId: number, fromSha: string, toSha: string) =>
    [...VERSION_KEYS.all, 'diff', videoId, fromSha, toSha] as const,
};

export const AUGMENT_KEYS = {
  all: ['augments'] as const,
  list: (params: Record<string, unknown>) => [...AUGMENT_KEYS.all, 'list', params] as const,
  detail: (id: number) => [...AUGMENT_KEYS.all, 'detail', id] as const,
};

export const EXPORT_KEYS = {
  all: ['exports'] as const,
  list: (params: Record<string, unknown>) => [...EXPORT_KEYS.all, 'list', params] as const,
  detail: (id: number) => [...EXPORT_KEYS.all, 'detail', id] as const,
};

/**
 * 비식별 신고 관리(REVIEWER) — BE GET /v1/deident-reports.
 * 상태/페이지별로 키를 분리하여 React Query 가 캐시를 분리 관리한다.
 */
export const DEIDENT_REPORT_KEYS = {
  all: ['deidentReports'] as const,
  list: (params: Record<string, unknown>) =>
    [...DEIDENT_REPORT_KEYS.all, 'list', params] as const,
};

export const PORTAL_KEYS = {
  all: ['portal'] as const,
  uploads: (params: Record<string, unknown>) => [...PORTAL_KEYS.all, 'uploads', params] as const,
  uploadDetail: (uldSn: number) => [...PORTAL_KEYS.all, 'upload-detail', uldSn] as const,
  uploadFrameLabels: (uldFrmeSn: number) =>
    [...PORTAL_KEYS.all, 'upload-frame-labels', uldFrmeSn] as const,
  datamartVideos: (params: Record<string, unknown>) =>
    [...PORTAL_KEYS.all, 'datamart-videos', params] as const,
};

export const SYSCONFIG_KEYS = {
  all: ['sysconfig'] as const,
  presets: () => [...SYSCONFIG_KEYS.all, 'presets'] as const,
};

export const NOTICE_KEYS = {
  all: ['notices'] as const,
  lists: () => [...NOTICE_KEYS.all, 'list'] as const,
  list: (params: NoticeListParams) => [...NOTICE_KEYS.lists(), params] as const,
  details: () => [...NOTICE_KEYS.all, 'detail'] as const,
  detail: (id: number) => [...NOTICE_KEYS.details(), id] as const,
};

export const META_KEYS = {
  all: ['meta'] as const,
  byVideo: (videoId: number) => [...META_KEYS.all, 'video', videoId] as const,
};

/**
 * event_annotation(외부 VLM VQA/CoT) — BE GET/PUT /v1/videos/{rawSn}/event-annotation.
 * 영상(rawSn) 단위로 캐시를 분리 관리한다.
 */
export const EVENT_ANNOTATION_KEYS = {
  all: ['eventAnnotation'] as const,
  byVideo: (rawSn: number) => [...EVENT_ANNOTATION_KEYS.all, 'video', rawSn] as const,
};

export const AUTOLABEL_KEYS = {
  all: ['autolabel'] as const,
  byVideo: (videoId: number) => [...AUTOLABEL_KEYS.all, 'video', videoId] as const,
};

export const MARKING_KEYS = {
  all: ['markings'] as const,
};

export const STAT_KEYS = {
  all: ['stats'] as const,
  worker: (userId: number | string) => [...STAT_KEYS.all, 'worker', userId] as const,
  overall: () => [...STAT_KEYS.all, 'overall'] as const,
};

/**
 * 이벤트 타입 — BE GET /v1/event-types (필터 옵션), /v1/event-types/labels (코드→라벨 맵).
 * near-immutable 이라 한 세션 1회만 페치한다 (hooks staleTime: Infinity).
 */
export const EVENT_TYPE_KEYS = {
  all: ['eventTypes'] as const,
  options: () => [...EVENT_TYPE_KEYS.all, 'options'] as const,
  labels: () => [...EVENT_TYPE_KEYS.all, 'labels'] as const,
};
