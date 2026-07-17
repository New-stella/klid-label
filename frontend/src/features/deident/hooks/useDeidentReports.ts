import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';

import { DEIDENT_REPORT_KEYS } from '@/lib/queryKeys';

import { listDeidentReports, resolveDeidentReport } from '../reportApi';
import type { ListDeidentReportsParams } from '../reportTypes';

/**
 * 비식별 신고 목록 조회 (REVIEWER 신고 관리 화면).
 */
export function useDeidentReports(params: ListDeidentReportsParams = {}) {
  return useQuery({
    queryKey: DEIDENT_REPORT_KEYS.list(params as Record<string, unknown>),
    queryFn: () => listDeidentReports(params),
    // 페이지/필터 전환 시 이전 목록 유지(빈 상태 깜빡임 방지).
    placeholderData: keepPreviousData,
  });
}

/**
 * 비식별 신고 수동 해소 — 성공 시 신고 목록 캐시 무효화로 갱신.
 */
export function useResolveDeidentReport(options?: {
  onSuccess?: () => void;
  onError?: (e: unknown) => void;
}) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (rprtSn: number) => resolveDeidentReport(rprtSn),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DEIDENT_REPORT_KEYS.all });
      options?.onSuccess?.();
    },
    onError: (e) => options?.onError?.(e),
  });
}
