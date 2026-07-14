// blocker#2 Phase 3 — 프레임 설명 조회/저장 훅 (TanStack Query).
//
// 서버 상태는 TanStack Query 로 관리(state-management.md). 컴포넌트는 이 커스텀 훅만 사용.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  getFrameDescription,
  putFrameDescription,
  type FrameDescription,
} from '../api/frameDescription';

export const FRAME_DESC_KEYS = {
  all: ['frameDescription'] as const,
  detail: (srcSn: number) => [...FRAME_DESC_KEYS.all, srcSn] as const,
  /** srcSn 미지정(비활성) 시 폴백 키 — all 과 분리해 다른 쿼리와 캐시 충돌 방지. */
  idle: () => [...FRAME_DESC_KEYS.all, 'idle'] as const,
};

/** 현재 프레임의 설명 조회. srcSn 없으면 비활성. */
export function useFrameDescription(srcSn: number | undefined) {
  return useQuery<FrameDescription>({
    queryKey: srcSn !== undefined ? FRAME_DESC_KEYS.detail(srcSn) : FRAME_DESC_KEYS.idle(),
    queryFn: () => getFrameDescription(srcSn as number),
    enabled: srcSn !== undefined,
  });
}

export interface UseUpdateFrameDescriptionOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/** 프레임 설명 저장 mutation. 성공 시 해당 프레임 쿼리 무효화 → 재로드. */
export function useUpdateFrameDescription(
  srcSn: number | undefined,
  options: UseUpdateFrameDescriptionOptions = {},
) {
  const qc = useQueryClient();
  return useMutation<FrameDescription, unknown, string | null>({
    mutationFn: (description: string | null) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      return putFrameDescription(srcSn, description);
    },
    onSuccess: () => {
      if (srcSn !== undefined) {
        qc.invalidateQueries({ queryKey: FRAME_DESC_KEYS.detail(srcSn) });
      }
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
