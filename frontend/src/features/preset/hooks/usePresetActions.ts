import { useMutation, useQueryClient } from '@tanstack/react-query';

import { clonePreset, createPreset, deletePreset, updatePreset } from '../api';
import type { PresetForm } from '../schemas';
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

  const clone = useMutation({
    mutationFn: (id: number) => clonePreset(id),
    onSuccess: invalidate,
  });

  return { create, update, remove, clone };
}
