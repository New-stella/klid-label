import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { REVIEW_KEYS } from '@/lib/queryKeys';

import { listReviews } from '../api';
import type { ReviewListParams } from '../types';

/**
 * 검수 목록 조회 (REVIEWER 전용) — 필터·정렬·페이징을 **서버에 위임**한다.
 *
 * ⚠ 이 엔드포인트의 정렬 정책은 **관용(lenient)** 이다: 미등록 `sort` 키는 400 이 아니라
 * 조용히 기본 정렬(제출일 최신순)로 폴백한다. 따라서 잘못된 정렬 키는 에러가 아니라
 * "헤더를 눌렀는데 순서가 그대로" 로만 드러난다 — allowlist 는 FE 가 스스로 지켜야 한다
 * (`reviewListParams.ts` 의 `REVIEW_SORT_COLUMNS`).
 * ★ 작업목록(`/v1/tasks/board`)은 **정반대로 strict(400)** 다. 두 화면 코드를 교차 재사용할 때 주의.
 *
 * 마찬가지로 `status` 도 화이트리스트 밖 값이면 400 이 아니라 **빈 결과 200** 이다 —
 * FE→BE 코드 역매핑은 `api.ts` 의 `REVIEW_STATUS_TO_BE` 한 곳에서만 한다.
 */
export function useReviewList(params: ReviewListParams) {
  return useQuery({
    queryKey: REVIEW_KEYS.pending(params as Record<string, unknown>),
    queryFn: () => listReviews(params),
    // 페이지/필터 전환 시 이전 목록 유지(빈 상태 깜빡임 방지).
    placeholderData: keepPreviousData,
  });
}
