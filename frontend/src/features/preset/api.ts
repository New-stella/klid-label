// 프리셋 도메인 API — BE: /api/v1/manage/presets
//
// 보안:
// - 모든 입력은 zod presetSchema로 검증 후 호출
// - 이름/설명/labelCodes는 axios JSON body로만 전달 (SQL/Path injection 방지)

import { apiClient } from '@/lib/api/client';

import { presetSchema } from './schemas';
import type { Preset, PresetForm } from './types';

export function listPresets() {
  return apiClient.get<Preset[]>('/manage/presets').then((r) => r.data);
}

export function createPreset(form: PresetForm) {
  const safe = presetSchema.parse(form);
  return apiClient.post<Preset>('/manage/presets', safe).then((r) => r.data);
}

export function updatePreset(id: number, form: PresetForm) {
  const safe = presetSchema.parse(form);
  return apiClient.put<Preset>(`/manage/presets/${id}`, safe).then((r) => r.data);
}

export function deletePreset(id: number) {
  return apiClient.delete(`/manage/presets/${id}`).then(() => undefined);
}

/** 프리셋 복사 — 이름에 ' (복사본)' 접미사 부여, 서버에서 신규 ID 발급. */
export function clonePreset(id: number) {
  return apiClient.post<Preset>(`/manage/presets/${id}/clone`).then((r) => r.data);
}
