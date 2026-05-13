import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { LABEL_KEYS } from '@/lib/queryKeys';

import { getLabels } from '../api';

/**
 * 프레임의 라벨 목록 조회.
 *
 * placeholderData: keepPreviousData — 프레임 전환 시 페이지 깜빡임/재생 끊김 방지.
 *   - srcSn 변경 시 isLoading 은 false 유지(첫 fetch 만 true), isFetching 만 true
 *   - 이전 data 가 유지되므로 LabelingPage 의 `if (isLoading) return <Spinner>` 분기를
 *     타지 않아 DarkFrameSlider 가 unmount 되지 않음 → 재생 인터벌(setInterval) 유지
 */
export function useLabels(srcSn: number | undefined) {
  return useQuery({
    queryKey: srcSn !== undefined ? LABEL_KEYS.byFrame(srcSn, 0) : LABEL_KEYS.all,
    queryFn: () => getLabels(srcSn as number),
    enabled: srcSn !== undefined,
    placeholderData: keepPreviousData,
  });
}
