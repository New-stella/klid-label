// 프리셋 도메인 API — BE: /api/v1/manage/presets
//
// 보안:
// - 모든 입력은 zod presetSchema로 검증 후 호출 (labelIds: number[], min 1)
// - name/description/labelIds/eventTypeCd 는 axios JSON body로만 전달 (SQL/Path injection 방지)
//
// Phase 4 (마스터 연동):
// - 요청 body 는 labelIds(number[]) 만 전송 — 형태는 마스터가 소유하므로 미전송.
// - 응답 코드 항목은 마스터 실시간 join 결과(labelId/labelName/labelType/linked/bbox/polygon)를 담는다.
// - legacy(labelCodes 만 있는) 응답은 미연결 코드로 안전 매핑한다.

import { LABEL_MASTER_TYPES, type LabelMasterType } from '@/features/label/api/labelMaster';
import { apiClient } from '@/lib/api/client';

import { presetSchema } from './schemas';
import type { Preset, PresetCode, PresetForm } from './types';

interface LabelCodeOptionResponse {
  labelId?: number | null;
  code?: string | null;
  labelName?: string | null;
  labelType?: string | null;
  linked?: boolean;
  bboxEnabled?: boolean;
  polygonEnabled?: boolean;
}

interface PresetResponse {
  id: number;
  name: string;
  description: string | null;
  /** 레거시 호환 — 라벨명 목록. labelCodeOptions 부재 시 fallback. */
  labelCodes?: string[];
  labelCodeOptions?: LabelCodeOptionResponse[];
  eventTypeCd?: string | null;
  createdAt: string;
  updatedAt: string;
}

/** BE 형태 코드를 알려진 카테고리로 정규화. 미지/누락은 null(미연결·형태없음). */
function normalizeType(raw: unknown): LabelMasterType | null {
  return (LABEL_MASTER_TYPES as readonly string[]).includes(String(raw))
    ? (String(raw) as LabelMasterType)
    : null;
}

/** 응답 코드 1건 → PresetCode. linked 여부·형태를 안전 정규화한다. */
function toPresetCode(o: LabelCodeOptionResponse): PresetCode {
  const linked = o.linked === true;
  const code = o.code ?? null;
  return {
    labelId: o.labelId ?? null,
    code,
    labelName: String(o.labelName ?? code ?? ''),
    labelType: normalizeType(o.labelType),
    linked,
    bboxEnabled: o.bboxEnabled === true,
    polygonEnabled: o.polygonEnabled === true,
  };
}

/**
 * 응답을 Preset 으로 정규화.
 * - labelCodeOptions 가 있으면 마스터 join 결과를 그대로 매핑.
 * - 없고 labelCodes 만 있으면(레거시) 미연결 코드로 매핑.
 */
function normalizePreset(raw: PresetResponse): Preset {
  const options = raw.labelCodeOptions;
  const codes: PresetCode[] =
    options && options.length > 0
      ? options.map(toPresetCode)
      : (raw.labelCodes ?? []).map((code) => ({
          labelId: null,
          code,
          labelName: code,
          labelType: null,
          linked: false,
          bboxEnabled: false,
          polygonEnabled: false,
        }));

  return {
    id: raw.id,
    name: raw.name,
    description: raw.description ?? null,
    codes,
    eventTypeCd: raw.eventTypeCd ?? null,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
  };
}

/** 폼을 BE 송신용 페이로드로 변환 — labelIds 만 전송(형태는 마스터 소유). */
function toPayload(form: PresetForm) {
  return {
    name: form.name,
    description: form.description,
    labelIds: form.labelIds,
    eventTypeCd: form.eventTypeCd,
  };
}

export function listPresets() {
  return apiClient
    .get<PresetResponse[]>('/manage/presets')
    .then((r) => r.data.map(normalizePreset));
}

export function createPreset(form: PresetForm) {
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
