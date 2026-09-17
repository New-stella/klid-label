import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ASSIGNMENT_KEYS, REVIEW_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';

import { approveReviewsBatch } from '../api';
import type { BatchApproveRequest, BatchApproveResponse } from '../types';

interface MutationOptions {
  onSuccess?: (data: BatchApproveResponse) => void;
  onError?: (err: unknown) => void;
}

/**
 * 검수 일괄 승인 — 고른 여러 영상을 한 번에 검수완료한다.
 *
 * <h3>★성공 응답이 「전건 성공」을 뜻하지 않는다</h3>
 * 부분 실패를 허용하는 창구라 **한 건도 승인되지 못해도 `onSuccess` 로 들어온다**. 호출부는
 * `successCount`/`failureCount` 와 건별 `results` 로 판정해야 하며, `onError` 를 실패 표시의
 * 유일한 경로로 삼으면 「전부 실패했는데 성공으로 보이는」 화면이 된다.
 * `onError` 로 오는 것은 요청 **자체**가 거부된 경우(빈 목록·건수 상한 초과 400, 인증·인가)다.
 *
 * <h3>왜 승인 3종을 모두 무효화하는가</h3>
 * 단건 승인({@link useApproveReview})과 **같은 부수효과**를 낸다 — 검수 상태·영상·배정이 함께
 * 움직인다. 한 종류라도 빠지면 목록은 갱신됐는데 다른 화면이 옛 상태를 보여준다.
 * 단건 승인이 무효화하는 키 집합을 그대로 따른다(두 경로가 갈리면 어느 한쪽만 신선해진다).
 *
 * <p>여기서 무효화하는 것은 **승인이 받아들여졌다**는 사실까지다 — 산출물 재생성·관제 통지는
 * 뒤에서 비동기로 이어지므로 그 완료를 기다리지 않는다.
 *
 * @design API-250
 * @design AC-1113
 */
export function useBatchApproveReviews(options: MutationOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: BatchApproveRequest) => approveReviewsBatch(body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
