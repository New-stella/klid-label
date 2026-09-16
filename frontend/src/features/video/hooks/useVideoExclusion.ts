import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ASSIGNMENT_KEYS, REVIEW_KEYS, TASK_BOARD_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';

import { excludeVideo, restoreVideo } from '../api';
import type { VideoExclusionResult, VideoRestoreResult } from '../api';

/**
 * 영상 제외·복원 mutation. [@design API-260] [@design API-261] [@design ADR-069]
 *
 * <h3>왜 네 캐시를 함께 버리는가</h3>
 * 이 한 번의 조작이 <b>목록 세 곳</b>(영상 처리 현황 · 작업 목록 · 검수 목록)의 결과와 그 건수·집계를
 * 동시에 바꾼다. 영상 캐시만 버리면 작업 목록·검수 목록은 방금 감춘 영상을 계속 보여주고, 그
 * 화면에서 배정·검수를 시도하면 서버가 그때서야 물리친다.
 *
 * <ul>
 *   <li>{@code VIDEO_KEYS} — 영상 처리 현황 목록과 「제외됨 N건」(이 화면은 페이지 응답이 싣는다)</li>
 *   <li>{@code TASK_BOARD_KEYS} — 작업 목록과 그 KPI 집계(집계가 「제외됨 N건」을 싣는다)</li>
 *   <li>{@code REVIEW_KEYS} — 검수 목록과 그 집계(같은 이유)</li>
 *   <li>{@code ASSIGNMENT_KEYS} — 작업자 축 배정 목록</li>
 * </ul>
 *
 * ⚠ <b>세 목록의 「제외됨 N건」은 각자 다른 창구에서 온다</b> — 하나만 버리면 숫자와 목록이
 * 어긋난 화면이 남는다. 「영상만 바뀌었으니 영상 캐시만」으로 좁히지 말 것.
 *
 * ⚠ `invalidateQueries` 로 충분하다 — 이 조작은 <b>게이트를 닫지 않는다</b>. 제외는 화면 시야만
 * 바꾸고 서버가 이후 요청을 물리치게 만들지 않으며, 라벨링 상세 진입도 막지 않는다(그것이 이
 * 결정의 명시적 범위다). 캐시에 남은 목록이 잠시 낡는 것뿐이라 `removeQueries` 가 필요 없다.
 */
export interface UseVideoExclusionOptions<T> {
  onSuccess?: (data: T) => void;
  onError?: (err: unknown) => void;
}

/** 제외·복원이 함께 버리는 캐시 — 두 훅이 같은 목록을 쓰도록 한 곳에 둔다. */
function invalidateExclusionScopes(qc: ReturnType<typeof useQueryClient>) {
  qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
  qc.invalidateQueries({ queryKey: TASK_BOARD_KEYS.all });
  qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
  qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
}

export interface ExcludeVideoArgs {
  rawSn: number;
  reason: string;
}

/**
 * 영상 제외 — 사유 필수.
 *
 * ★배정이 있으면 서버가 409 로 물리친다. 화면은 그 전에 버튼을 미리 비활성화하지만, 판정과
 * 요청 사이에 배정이 새로 생길 수 있어 <b>여기서도 실패가 올라온다</b> — 호출부는 그 실패를
 * 삼키지 말고 서버 문구를 그대로 보여준다(그 문구가 배정 해제라는 다음 행동을 가리킨다).
 */
export function useExcludeVideo(options: UseVideoExclusionOptions<VideoExclusionResult> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ rawSn, reason }: ExcludeVideoArgs) => excludeVideo(rawSn, reason),
    onSuccess: (data) => {
      invalidateExclusionScopes(qc);
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 영상 복원 — 사유를 받지 않는다(감추는 쪽만 사유를 남긴다).
 *
 * 복원은 아무것도 재생성하지 않고 관제 수정 통지도 내지 않는다 — 화면 시야만 되돌린다.
 */
export function useRestoreVideo(options: UseVideoExclusionOptions<VideoRestoreResult> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (rawSn: number) => restoreVideo(rawSn),
    onSuccess: (data) => {
      invalidateExclusionScopes(qc);
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
