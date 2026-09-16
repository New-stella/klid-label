import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ASSIGNMENT_KEYS, TASK_BOARD_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';

import { unassignTask } from '../api';

export interface UseUnassignTaskOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/**
 * 배정 해제 mutation — DELETE /assignments/{assignmentId}.
 * [@design API-259] [@design AC-1123] [@design ADR-069]
 *
 * <p>무효화 대상은 <b>재배정({@code useReassignTask})과 같다</b> — 같은 배정 행을 건드리고
 * 같은 세 화면(배정 목록 · 영상 목록 · 작업 목록)이 그 결과를 보여주기 때문이다. 하나라도
 * 빠뜨리면 그 화면의 작업자 칸이 옛 값으로 남는다.
 *
 * <p>★<b>영상 목록도 함께 버린다</b> — 해제는 곧 그 영상의 제외 가능 여부를 뒤집는다.
 * 영상 처리 현황의 「제외」 버튼은 배정 유무로 미리 비활성화되므로, 그 목록을 갱신하지 않으면
 * 배정을 방금 풀었는데도 버튼이 계속 잠겨 있어 <b>「배정 해제 → 제외」 경로가 화면에서 끊긴다</b>
 * (그 경로가 끊기면 제외 거부 안내가 다시 막다른 길이 된다).
 *
 * <p>응답 본문이 없다(204) — 성공 콜백도 인자를 받지 않는다.
 */
export function useUnassignTask(options: UseUnassignTaskOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (assignmentId: number) => unassignTask(assignmentId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: TASK_BOARD_KEYS.all });
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
