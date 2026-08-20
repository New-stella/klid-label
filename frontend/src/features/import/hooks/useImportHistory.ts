// 이관 이력 조회 훅.
//
// @design API-207 API-208

import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { IMPORT_KEYS } from '@/lib/queryKeys';

import { getImportHistoryDetail, listImportHistory } from '../api';
import type { ListImportHistoryParams } from '../types';

/** 이관 이력 목록 — 최근순. 정렬 기준은 서버가 고정하므로 요청이 고르지 않는다. */
export function useImportHistory(params: ListImportHistoryParams = {}) {
  return useQuery({
    queryKey: IMPORT_KEYS.historyList(params as Record<string, unknown>),
    queryFn: () => listImportHistory(params),
    // 쪽·필터 전환 시 이전 목록 유지(빈 상태 깜빡임 방지).
    placeholderData: keepPreviousData,
  });
}

/**
 * 이관 이력 상세 — 실패 사유를 펼쳐볼 때만 조회한다(`trnsfSn` 이 null 이면 비활성).
 *
 * 이력 행은 사후 수정하지 않는 감사 기록이라 한 번 받은 상세는 달라지지 않는다. 그래도
 * 캐시를 무한정 붙들지는 않고 목록과 같은 기본값을 쓴다.
 */
export function useImportHistoryDetail(trnsfSn: number | null) {
  return useQuery({
    queryKey: IMPORT_KEYS.historyDetail(trnsfSn ?? -1),
    queryFn: () => getImportHistoryDetail(trnsfSn as number),
    enabled: trnsfSn !== null,
  });
}
