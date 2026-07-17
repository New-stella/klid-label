// Phase 5 — 포털 업로드 자산 목록 훅. 후처리 진행 중(UPLOADED/PROCESSING) 자산이 있으면
// 3초 폴링하여 READY|FAILED 전환을 반영한다.
import { useQuery } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { listUploads, type ListUploadsParams } from '../api';
import { IN_PROGRESS_STATUSES, type PortalUpload } from '../types';

/** 폴링 간격(ms). */
export const POLL_MS = 3000;

/**
 * 목록에 후처리 진행 중 자산이 하나라도 있으면 폴링 간격을, 모두 종결이면 false 를 반환한다.
 * (React Query refetchInterval 계약: number = 폴링, false = 중단)
 */
export function pollIntervalFor(uploads: PortalUpload[] | undefined): number | false {
  if (!uploads || uploads.length === 0) return false;
  const hasInProgress = uploads.some((u) => IN_PROGRESS_STATUSES.includes(u.uldSttsCd));
  return hasInProgress ? POLL_MS : false;
}

export function usePortalUploads(params: ListUploadsParams = {}) {
  return useQuery({
    queryKey: PORTAL_KEYS.uploads(params as Record<string, unknown>),
    queryFn: () => listUploads(params),
    // 진행 중 자산이 있는 동안만 폴링 — 종결되면 자동 중단(불필요한 요청 방지).
    refetchInterval: (query) => pollIntervalFor(query.state.data?.content),
  });
}
