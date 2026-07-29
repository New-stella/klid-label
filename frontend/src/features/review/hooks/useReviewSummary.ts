import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { REVIEW_KEYS } from '@/lib/queryKeys';

import { getReviewSummary } from '../api';
import type { ReviewSummaryParams } from '../types';

/**
 * 검수목록 KPI 집계 훅 — BE `GET /v1/reviews/summary`.
 *
 * **필터 결과 전체 기준** 숫자다(현재 페이지 20건 안에서 세지 않는다). 목록과 독립 쿼리이므로
 * 이 쿼리의 실패는 **목록 표시를 막지 않아야 한다** — 호출부는 이 훅의 `error` 를 페이지 레벨
 * 에러 계산에 넣지 말고 카드 영역에만 표시한다.
 *
 * REVIEWER 전용 엔드포인트라 `options.enabled` 로 비 REVIEWER 호출(403)을 차단한다.
 */
export function useReviewSummary(
  params: ReviewSummaryParams,
  options?: { enabled?: boolean },
) {
  return useQuery({
    queryKey: REVIEW_KEYS.summary(params as Record<string, unknown>),
    queryFn: () => getReviewSummary(params),
    enabled: options?.enabled ?? true,
    // 필터 전환 중 카드가 스켈레톤으로 깜빡이지 않게 이전 집계를 유지한다.
    placeholderData: keepPreviousData,
  });
}
