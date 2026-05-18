import { useMutation, useQueryClient } from '@tanstack/react-query';

import { LABEL_KEYS, VERSION_KEYS } from '@/lib/queryKeys';

import { rollback } from '../api';

/**
 * 라벨 롤백 mutation.
 *
 * 성공 시 다음을 invalidate 하여 라벨링 캔버스 + 작업이력 패널을 즉시 동기화:
 *  - LABEL_KEYS.all: 현재 프레임뿐 아니라 형제 프레임(siblings) 캐시도 함께 무효화.
 *    byVideo(videoId) 만 무효화할 경우 useLabels 의 queryKey 인 byFrame(srcSn, 0)
 *    과의 prefix 매칭 동작에 의존하게 되어, videoId/srcSn 인자 전달 누락이나 키 구조
 *    변경 시 회귀가 발생하기 쉽다. 안전을 위해 LABEL_KEYS.all 로 일괄 무효화한다.
 *  - VERSION_KEYS.all: 작업이력 패널의 신규 롤백 커밋 즉시 반영.
 *
 * placeholderData: keepPreviousData 인 useLabels 와 결합 시:
 *  invalidate → 백그라운드 refetch → data 변경 → LabelingPage 의 useEffect 가
 *  setLabels(data.labels) 호출 → 캔버스 갱신.
 *
 * 보안: 권한(REVIEWER) + commit SHA 검증은 BE에서 수행. FE는 단순 호출만.
 */
export function useRollback(videoId: number | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ commitSha, srcSn }: { commitSha: string; srcSn: number }) =>
      rollback(commitSha, srcSn),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      qc.invalidateQueries({ queryKey: VERSION_KEYS.all });
      if (videoId !== undefined) {
        // 명시적 prefix 키도 함께 invalidate — 기존 회귀 테스트 호환.
        qc.invalidateQueries({ queryKey: LABEL_KEYS.byVideo(videoId) });
        qc.invalidateQueries({ queryKey: VERSION_KEYS.history(videoId) });
      }
    },
  });
}
