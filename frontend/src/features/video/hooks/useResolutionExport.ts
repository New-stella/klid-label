import { useMutation } from '@tanstack/react-query';

import { changeResolution } from '../api';
import type { ResolutionPreset } from '../types';

/**
 * SFR-06-03 — 해상도 export mutation.
 * 선택한 프리셋으로 검수 완료 원본의 프레임 이미지셋을 다운스케일한다.
 * 업스케일/증강본/미검수/중복은 BE 가 400/409 → ApiError 로 전파되어 onError 에서 메시지 표시.
 */
export function useResolutionExport(rawSn: number) {
  return useMutation({
    mutationFn: (preset: ResolutionPreset) => changeResolution(rawSn, preset),
  });
}
