import { useMutation, useQueryClient } from '@tanstack/react-query';

import { createPreset, deletePreset, updatePreset } from '../api';
import type { PresetForm } from '../types';
import { PRESETS_QUERY_KEY } from './usePresets';

export function usePresetActions() {
  const qc = useQueryClient();

  const invalidate = () => qc.invalidateQueries({ queryKey: PRESETS_QUERY_KEY });

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
