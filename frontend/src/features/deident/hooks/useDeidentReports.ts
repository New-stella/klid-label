import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';

import { DEIDENT_REPORT_KEYS } from '@/lib/queryKeys';

import {
  listDeidentCandidates,
  listDeidentReports,
  resolveDeidentReport,
} from '../reportApi';
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
 * 재비식별 산출물 후보 조회 — 해소 다이얼로그가 열릴 때만 조회한다(`rprtSn`이 null 이면 비활성).
 *
 * 외부 솔루션이 방금 파일을 만들었을 수 있으므로 캐시를 오래 붙들지 않는다(`staleTime: 0`) —
 * 여기서 낡은 목록을 보여주면 사용자가 없는 파일을 고르고 서버가 400 으로 돌려보낸다.
 */
export function useDeidentCandidates(rprtSn: number | null) {
  return useQuery({
    queryKey: DEIDENT_REPORT_KEYS.candidates(rprtSn ?? -1),
    queryFn: () => listDeidentCandidates(rprtSn as number),
    enabled: rprtSn !== null,
    staleTime: 0,
  });
}

/**
 * 비식별 신고 수동 해소 — 성공 시 신고 목록·후보 캐시 무효화로 갱신.
 *
 * ★ `fileName`(어느 산출물로 해소하는가)은 필수다 — 서버가 기본값을 고르지 않으므로 호출부도
 * 사용자가 고른 값을 반드시 넘겨야 한다.
 */
export function useResolveDeidentReport(options?: {
  onSuccess?: () => void;
  onError?: (e: unknown) => void;
}) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ rprtSn, fileName }: { rprtSn: number; fileName: string }) =>
      resolveDeidentReport(rprtSn, fileName),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DEIDENT_REPORT_KEYS.all });
      options?.onSuccess?.();
    },
    onError: (e) => options?.onError?.(e),
  });
}
