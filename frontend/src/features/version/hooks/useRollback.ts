import { useMutation, useQueryClient } from '@tanstack/react-query';

import { LABEL_KEYS, VERSION_KEYS } from '@/lib/queryKeys';

import { rollback } from '../api';

/**
 * 라벨 롤백 mutation.
 * 성공 시 LABEL_KEYS.byVideo / VERSION_KEYS.history 무효화 → 라벨링 화면 자동 재조회.
 *
 * 보안: 권한(REVIEWER) + commit SHA 검증은 BE에서 수행. FE는 단순 호출만.
 */
export function useRollback(videoId: number | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ commitSha, srcSn }: { commitSha: string; srcSn: number }) =>
      rollback(commitSha, srcSn),
    onSuccess: () => {
      if (videoId !== undefined) {
        qc.invalidateQueries({ queryKey: LABEL_KEYS.byVideo(videoId) });
        qc.invalidateQueries({ queryKey: VERSION_KEYS.history(videoId) });
      }
    },
  });
}
