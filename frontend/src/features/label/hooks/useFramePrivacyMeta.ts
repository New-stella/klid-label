// Phase 4 — 프레임 개인정보 메타 조회/저장 훅 (TanStack Query).
//
// 서버 상태는 TanStack Query 로 관리(state-management.md). 컴포넌트는 이 커스텀 훅만 사용.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  getFramePrivacyMeta,
  putFramePrivacyMeta,
  type FramePrivacyMeta,
  type FramePrivacyMetaUpdate,
} from '../api/framePrivacyMeta';

export const FRAME_PRIVACY_KEYS = {
  all: ['framePrivacyMeta'] as const,
  detail: (srcSn: number) => [...FRAME_PRIVACY_KEYS.all, srcSn] as const,
  /** srcSn 미지정(비활성) 시 폴백 키 — 다른 쿼리와 캐시 충돌 방지. */
  idle: () => [...FRAME_PRIVACY_KEYS.all, 'idle'] as const,
};

/** 현재 프레임의 개인정보 메타 조회. srcSn 없으면 비활성. */
export function useFramePrivacyMeta(srcSn: number | undefined) {
  return useQuery<FramePrivacyMeta>({
    queryKey:
      srcSn !== undefined ? FRAME_PRIVACY_KEYS.detail(srcSn) : FRAME_PRIVACY_KEYS.idle(),
    queryFn: () => getFramePrivacyMeta(srcSn as number),
    enabled: srcSn !== undefined,
  });
}

export interface UseUpdateFramePrivacyMetaOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/** 개인정보 메타 저장 mutation. 성공 시 해당 프레임 쿼리 무효화 → 재로드. */
export function useUpdateFramePrivacyMeta(
  srcSn: number | undefined,
  options: UseUpdateFramePrivacyMetaOptions = {},
) {
  const qc = useQueryClient();
  return useMutation<FramePrivacyMeta, unknown, FramePrivacyMetaUpdate>({
    mutationFn: (body: FramePrivacyMetaUpdate) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      return putFramePrivacyMeta(srcSn, body);
    },
    onSuccess: () => {
      if (srcSn !== undefined) {
        qc.invalidateQueries({ queryKey: FRAME_PRIVACY_KEYS.detail(srcSn) });
      }
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
