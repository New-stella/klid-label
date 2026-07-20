import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { LABEL_KEYS } from '@/lib/queryKeys';

import { getLabelHistory } from '../api';

/** 라벨 변경 이력 기본 페이지 크기 (BE size 상한 100 이하). */
export const LABEL_HISTORY_PAGE_SIZE = 20;

/**
 * 프레임(srcSn)의 라벨 변경 이력 조회 (최신순 페이징).
 *
 * - LABEL_KEYS.history(srcSn, page) 로 페이지별 캐시 격리.
 * - 저장(useUpdateLabels) 성공 시 LABEL_KEYS.historyByFrame(srcSn) prefix 무효화로 새 이력 반영.
 * - placeholderData: keepPreviousData — 페이지 전환 시 리스트 깜빡임 방지.
 *
 * @param srcSn LS_DATA_SRC.SRC_SN — 프레임 PK. undefined 면 비활성(enabled=false).
 * @param page  0-based 페이지 번호.
 * @param size  페이지 크기(기본 20, BE 가 100 으로 클램프).
 */
export function useLabelHistory(
  srcSn: number | undefined,
  page = 0,
  size = LABEL_HISTORY_PAGE_SIZE,
) {
  return useQuery({
    queryKey:
      srcSn !== undefined ? LABEL_KEYS.history(srcSn, page) : LABEL_KEYS.all,
    queryFn: () => getLabelHistory(srcSn as number, page, size),
    enabled: srcSn !== undefined,
    placeholderData: keepPreviousData,
    staleTime: 15_000,
  });
}
