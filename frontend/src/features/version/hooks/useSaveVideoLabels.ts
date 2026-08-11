import { useMutation, useQueryClient } from '@tanstack/react-query';

import {
  ASSIGNMENT_KEYS,
  LABEL_KEYS,
  REVIEW_KEYS,
  VERSION_KEYS,
  VIDEO_KEYS,
} from '@/lib/queryKeys';

import { saveVideoLabels } from '../api';
import type { VideoLabelSavePayload, VideoLabelSaveResult } from '../types';

/**
 * API-196 — 영상 라벨 <b>확정 저장</b>.
 *
 * <h3>캐시 처리 — `invalidate` 이며 `remove` 가 아니다</h3>
 * 이 저장소의 구속 규칙은 <b>"게이트가 닫히는 변화만 removeQueries"</b> 다(비식별 신고 접수처럼 이후
 * 요청을 서버가 거부하게 되는 경우). 확정 저장은 서버가 거부하게 만드는 변화가 아니라 <b>값이 달라지는
 * 정합성 갱신</b>이므로 `invalidateQueries` 가 맞다.
 *
 * <h3>왜 이렇게 넓게 무효화하나</h3>
 * 이 한 번의 호출이 <b>영상 전체</b>의 라벨 본문과 프레임 폐기 상태를 확정한다. 현재 프레임만
 * 무효화하면 형제 프레임은 옛 라벨·옛 폐기 표식을 그대로 들고 있다. 게다가 `useLabels` 는
 * `staleTime: 30_000` + `refetchOnWindowFocus: false` 라 <b>무효화하지 않으면 30초 동안 서버를 아예
 * 때리지 않아</b> 사용자가 저장 전 라벨을 계속 본다.
 *  - `LABEL_KEYS.all`      : 영상 전체 프레임의 라벨 + siblings(폐기 표식 포함)
 *  - `VERSION_KEYS.all`    : 영상 버전 목록 + 프레임 버전 이력 + 작업본 diff(기준이 달라진다)
 *  - `VIDEO/ASSIGNMENT/REVIEW` : 진행률·목록 집계(폐기 프레임이 산출 대상에서 빠진다)
 *
 * @design API-196
 * @req R6
 */
export function useSaveVideoLabels(rawSn: number | undefined) {
  const qc = useQueryClient();
  return useMutation<VideoLabelSaveResult, unknown, VideoLabelSavePayload>({
    mutationFn: (payload: VideoLabelSavePayload) => {
      if (rawSn === undefined) return Promise.reject(new Error('rawSn is required'));
      return saveVideoLabels(rawSn, payload);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      qc.invalidateQueries({ queryKey: VERSION_KEYS.all });
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
    },
  });
}
