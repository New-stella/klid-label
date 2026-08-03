// 영상 단위 개인정보 메타 조회/저장 훅 (TanStack Query).
//
// 서버 상태는 TanStack Query 로 관리(state-management.md). 컴포넌트는 이 커스텀 훅만 사용.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  getVideoPrivacyMeta,
  putVideoPrivacyMeta,
  type VideoPrivacyMeta,
  type VideoPrivacyMetaUpdate,
} from '../api/videoPrivacyMeta';

export const VIDEO_PRIVACY_KEYS = {
  all: ['videoPrivacyMeta'] as const,
  detail: (rawSn: number) => [...VIDEO_PRIVACY_KEYS.all, rawSn] as const,
  /** rawSn 미지정(비활성) 시 폴백 키 — 다른 쿼리와 캐시 충돌 방지. */
  idle: () => [...VIDEO_PRIVACY_KEYS.all, 'idle'] as const,
};

/** 현재 영상의 개인정보 메타 조회. rawSn 없으면 비활성. */
export function useVideoPrivacyMeta(rawSn: number | undefined) {
  return useQuery<VideoPrivacyMeta>({
    queryKey:
      rawSn !== undefined ? VIDEO_PRIVACY_KEYS.detail(rawSn) : VIDEO_PRIVACY_KEYS.idle(),
    queryFn: () => getVideoPrivacyMeta(rawSn as number),
    enabled: rawSn !== undefined,
  });
}

export interface UseUpdateVideoPrivacyMetaOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/** 영상 개인정보 메타 저장 mutation. 성공 시 해당 영상 쿼리 무효화 → 재로드. */
export function useUpdateVideoPrivacyMeta(
  rawSn: number | undefined,
  options: UseUpdateVideoPrivacyMetaOptions = {},
) {
  const qc = useQueryClient();
  return useMutation<VideoPrivacyMeta, unknown, VideoPrivacyMetaUpdate>({
    mutationFn: (body: VideoPrivacyMetaUpdate) => {
      if (rawSn === undefined) {
        return Promise.reject(new Error('rawSn is required'));
      }
      return putVideoPrivacyMeta(rawSn, body);
    },
    onSuccess: () => {
      if (rawSn !== undefined) {
        qc.invalidateQueries({ queryKey: VIDEO_PRIVACY_KEYS.detail(rawSn) });
      }
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
