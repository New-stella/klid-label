import { useMutation, useQueryClient } from '@tanstack/react-query';

import { VIDEO_KEYS } from '@/lib/queryKeys';

import { changeResolution } from '../api';
import type { ResolutionPreset } from '../types';

/**
 * SFR-06-03 — 해상도 변경(파생영상 생성) mutation.
 *
 * <p>원본에서 목표 해상도별 새 파생영상(RAW_SN)을 만들어 검수 파이프라인(PENDING)에 넣는다.
 * presets 미지정/빈 배열이면 BE 가 표준 3종 전체를 생성한다.
 *
 * <p>성공 시 파생영상이 목록/검수 큐에 새로 뜨므로 영상 목록 쿼리를 invalidate 한다.
 * 업스케일/증강본/미검수는 400, 전부 실패는 500 → ApiError 로 전파되어 onError 에서 메시지 표시.
 */
export function useResolutionDerivative(rawSn: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (presets?: ResolutionPreset[]) =>
      changeResolution(rawSn, presets),
    onSuccess: () => {
      // 검수 대기 파생영상이 목록/검수 큐에 뜨도록 영상 목록 전체 캐시 무효화.
      queryClient.invalidateQueries({ queryKey: VIDEO_KEYS.all });
    },
  });
}
