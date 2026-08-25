// 프리셋 도메인 API — BE: /api/v1/manage/presets
//
// 계약: 프리셋은 <이벤트유형 1건 + 라벨 목록> 둘뿐이다(V17).
// - 요청 body 는 `{ eventTypeCd, labelIds }` 만. 이름·설명은 없어졌고, 형태는 마스터가
//   소유하므로 전송하지 않는다.
// - 응답 코드 항목은 마스터 실시간 join 결과(labelId/labelName/labelType/linked/bbox/polygon)를 담고,
//   이벤트 표시명(`eventTypeNm`)은 서버가 4단 폴백으로 해석해 실어 준다.
// - legacy(labelCodes 만 있는) 응답은 미연결 코드로 안전 매핑한다.
//
// 보안: 모든 입력은 zod presetSchema 로 검증 후 호출하고, 값은 axios JSON body 로만 전달한다
// (SQL/Path injection 방지).
//
// @design SCREEN-026
// @design API-037
// @design API-038
// @design API-039

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
  /** 레거시 호환 — 라벨명 목록. labelCodeOptions 부재 시 fallback. */
  labelCodes?: string[];
  labelCodeOptions?: LabelCodeOptionResponse[];
  eventTypeCd?: string | null;
  /** 서버 해석 표시명(4단 폴백). 최종 폴백이 유형코드라 항상 채워진다. */
  eventTypeNm?: string | null;
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
 *
 * ★`eventTypeNm` 이 비어 온 응답(구 서버·부분 응답)은 <b>유형코드로 폴백</b>한다 —
 *   화면이 이벤트 목록으로 역해석하지 않기 위한 최소 안전망이다. 폴백 규칙(운영자 표시명 →
 *   관제 수신명 → 카테고리명 → 유형코드)을 여기서 재현하지 않는다. 판정은 서버 한 곳이다.
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

  const eventTypeCd = raw.eventTypeCd ?? '';
  return {
    id: raw.id,
    eventTypeCd,
    eventTypeNm: raw.eventTypeNm ?? eventTypeCd,
    codes,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
  };
}

/** 폼을 BE 송신용 페이로드로 변환 — 이벤트유형 + labelIds 둘뿐(형태는 마스터 소유). */
function toPayload(form: PresetForm) {
  return {
    eventTypeCd: form.eventTypeCd,
    labelIds: form.labelIds,
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
