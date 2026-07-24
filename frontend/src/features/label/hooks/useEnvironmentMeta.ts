// Phase 4 — 영상 촬영환경 메타 조회/저장 훅 (TanStack Query).
//
// 서버 상태는 TanStack Query 로 관리(state-management.md). 컴포넌트는 이 커스텀 훅만 사용.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  getEnvironmentMeta,
  putEnvironmentMeta,
  type EnvironmentMeta,
  type EnvironmentMetaUpdate,
} from '../api/environmentMeta';

export const ENV_META_KEYS = {
  all: ['environmentMeta'] as const,
  detail: (rawSn: number) => [...ENV_META_KEYS.all, rawSn] as const,
  /** rawSn 미지정(비활성) 시 폴백 키 — 다른 쿼리와 캐시 충돌 방지. */
  idle: () => [...ENV_META_KEYS.all, 'idle'] as const,
};

/** 현재 영상의 촬영환경 조회. rawSn 없으면 비활성. */
export function useEnvironmentMeta(rawSn: number | undefined) {
  return useQuery<EnvironmentMeta>({
    queryKey: rawSn !== undefined ? ENV_META_KEYS.detail(rawSn) : ENV_META_KEYS.idle(),
    queryFn: () => getEnvironmentMeta(rawSn as number),
    enabled: rawSn !== undefined,
  });
}

export interface UseUpdateEnvironmentMetaOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/** 촬영환경 저장 mutation. 성공 시 해당 영상 쿼리 무효화 → 재로드. */
export function useUpdateEnvironmentMeta(
  rawSn: number | undefined,
  options: UseUpdateEnvironmentMetaOptions = {},
) {
  const qc = useQueryClient();
  return useMutation<EnvironmentMeta, unknown, EnvironmentMetaUpdate>({
    mutationFn: (body: EnvironmentMetaUpdate) => {
      if (rawSn === undefined) {
        return Promise.reject(new Error('rawSn is required'));
      }
      return putEnvironmentMeta(rawSn, body);
    },
    onSuccess: () => {
      if (rawSn !== undefined) {
        qc.invalidateQueries({ queryKey: ENV_META_KEYS.detail(rawSn) });
      }
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
