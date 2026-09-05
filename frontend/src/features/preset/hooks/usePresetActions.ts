import { useMutation, useQueryClient } from '@tanstack/react-query';

import { EVENT_TYPE_ADMIN_KEY } from '@/features/eventType/adminHooks';

import { createPreset, deletePreset, updatePreset } from '../api';
import type { PresetForm } from '../types';
import { PRESETS_QUERY_KEY } from './usePresets';

export function usePresetActions() {
  const qc = useQueryClient();

  /**
   * 프리셋을 만들거나 고치거나 지우면 <b>이벤트유형의 프리셋 연결 상태도 함께 바뀐다</b> —
   * 그래서 이벤트유형 관리 목록도 무효화한다. 안 하면 그 화면이 최대 staleTime 동안 옛 연결
   * 상태를 보여주고, 운영자는 방금 프리셋을 걸어 놓고도 '미연결' 을 계속 본다.
   *
   * ⚠ 접두 무효화라 <b>거르기 조건별 캐시 전부</b>가 대상이다(조건마다 키가 갈려 있다).
   */
  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: EVENT_TYPE_ADMIN_KEY });
    return qc.invalidateQueries({ queryKey: PRESETS_QUERY_KEY });
  };

  const create = useMutation({
    mutationFn: (form: PresetForm) => createPreset(form),
    onSuccess: invalidate,
  });

  const update = useMutation({
    mutationFn: ({ id, form }: { id: number; form: PresetForm }) => updatePreset(id, form),
    onSuccess: invalidate,
  });

  const remove = useMutation({
    mutationFn: (id: number) => deletePreset(id),
    onSuccess: invalidate,
  });

  // 복제(clone)는 폐지됐다 — 프리셋은 이벤트유형 1건에 1건만 대응하므로 복제 대상이 없고,
  // 복제 결과(이벤트 미연결)는 어느 영상에도 매칭되지 않는 죽은 행이 된다. 서버 엔드포인트도 없다.
  return { create, update, remove };
}
