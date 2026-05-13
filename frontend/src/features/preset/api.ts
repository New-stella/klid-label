// 프리셋 도메인 API — BE: /api/v1/manage/presets
//
// 보안:
// - 모든 입력은 zod presetSchema로 검증 후 호출
// - 이름/설명/labelCodes/labelCodeOptions는 axios JSON body로만 전달 (SQL/Path injection 방지)
//
// Phase 3 (V1.7):
// - 요청 body 에 `labelCodeOptions` 동봉 (BE 신규 필드)
// - 응답에 labelCodeOptions 가 없고 labelCodes 만 있으면 모두 (true,true) 로 normalize

import { apiClient } from '@/lib/api/client';

import { presetSchema } from './schemas';
import type { LabelCodeOption, Preset, PresetForm } from './types';

interface PresetResponse {
  id: number;
  name: string;
  description: string | null;
  labelCodes?: string[];
  labelCodeOptions?: LabelCodeOption[];
  eventTypeCd?: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * 응답을 정규화 — labelCodeOptions 가 없으면 labelCodes 로부터 (true,true) 로 생성.
 * 반대로 labelCodes 가 없으면 labelCodeOptions 에서 code 만 추출해 동기화.
 */
function normalizePreset(raw: PresetResponse): Preset {
  const fromOptions = raw.labelCodeOptions ?? null;
  const fromCodes = raw.labelCodes ?? null;

  const options: LabelCodeOption[] =
    fromOptions && fromOptions.length > 0
      ? fromOptions.map((o) => ({
          code: o.code,
          bboxEnabled: o.bboxEnabled,
          polygonEnabled: o.polygonEnabled,
        }))
      : (fromCodes ?? []).map((code) => ({
          code,
          bboxEnabled: true,
          polygonEnabled: true,
        }));

  const codes = options.map((o) => o.code);

  return {
    id: raw.id,
    name: raw.name,
    description: raw.description ?? null,
    labelCodes: codes,
    labelCodeOptions: options,
    eventTypeCd: raw.eventTypeCd ?? null,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
  };
}

/**
 * 폼을 BE 송신용 페이로드로 변환.
 *
 * - labelCodeOptions 가 비어있고 labelCodes 만 있는 레거시 폼은 모두 (true,true) 로 normalize.
 * - BE 호환 위해 labelCodes 도 함께 전송 (BE 가 둘 다 받아 처리).
 */
function toPayload(form: PresetForm) {
  const options: LabelCodeOption[] =
    form.labelCodeOptions && form.labelCodeOptions.length > 0
      ? form.labelCodeOptions
      : form.labelCodes.map((code) => ({
          code,
          bboxEnabled: true,
          polygonEnabled: true,
        }));
  return {
    name: form.name,
    description: form.description,
    labelCodes: options.map((o) => o.code),
    labelCodeOptions: options,
    eventTypeCd: form.eventTypeCd,
  };
}

export function listPresets() {
  return apiClient
    .get<PresetResponse[]>('/manage/presets')
    .then((r) => r.data.map(normalizePreset));
}

export function createPreset(form: PresetForm) {
  // 스키마 검증 — labelCodeOptions 우선, 없으면 labelCodes 로 normalize.
  presetSchema.parse(form);
  return apiClient
    .post<PresetResponse>('/manage/presets', toPayload(form))
    .then((r) => normalizePreset(r.data));
}

export function updatePreset(id: number, form: PresetForm) {
  presetSchema.parse(form);
  return apiClient
    .put<PresetResponse>(`/manage/presets/${id}`, toPayload(form))
    .then((r) => normalizePreset(r.data));
}

export function deletePreset(id: number) {
  return apiClient.delete(`/manage/presets/${id}`).then(() => undefined);
}

/** 프리셋 복사 — 이름에 ' (복사본)' 접미사 부여, 서버에서 신규 ID 발급. */
export function clonePreset(id: number) {
  return apiClient
    .post<PresetResponse>(`/manage/presets/${id}/clone`)
    .then((r) => normalizePreset(r.data));
}
