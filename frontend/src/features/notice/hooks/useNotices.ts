import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { NOTICE_KEYS } from '@/lib/queryKeys';

import { getNotice, listNotices } from '../api';
import type { NoticeListParams } from '../types';

/**
 * 공지 목록 — BE /v1/notices 페이징.
 * 고정 우선 → 최신순은 BE 정렬을 그대로 사용한다.
 * WORKER 는 PUBLISHED 만 응답받는다(BE 가시성 규칙).
 */
export function useNotices(params: NoticeListParams) {
  return useQuery({
    queryKey: NOTICE_KEYS.list(params),
    queryFn: () => listNotices(params),
    staleTime: 30_000,
    // 페이지 전환 시 이전 목록 유지(빈 상태 깜빡임 방지).
    placeholderData: keepPreviousData,
  });
}

/**
 * 공지 상세 — BE /v1/notices/{id}.
 * WORKER 가 DRAFT 조회 시 BE 가 404 를 반환한다.
 */
export function useNotice(id: number | undefined) {
  return useQuery({
    queryKey: NOTICE_KEYS.detail(id ?? -1),
    queryFn: () => getNotice(id as number),
    enabled: typeof id === 'number' && id > 0,
  });
}
