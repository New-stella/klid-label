// Phase 2 — 라벨별 속성 정의(LabelAttr) CRUD API 클라이언트.
//
// BE 계약 (REVIEWER):
//   GET    /v1/manage/labels/{labelId}/attrs           (List<LabelAttrResponse>)
//   POST   /v1/manage/labels/{labelId}/attrs           (201)
//   PUT    /v1/manage/labels/{labelId}/attrs/{attrId}   (200)
//   DELETE /v1/manage/labels/{labelId}/attrs/{attrId}   (204)
// 동일 이름 중복 시 409, 검증 실패 시 400.
//
// 응답은 ApiResponse<T> 래퍼에서 client interceptor 가 data 만 꺼내 반환.
//
// 보안:
//  - 권한(REVIEWER)은 BE(SecurityConfig + @PreAuthorize)에서 강제.
//  - 요청 본문은 LabelAttrUpsert(허용 필드만) 로 한정 — Mass Assignment(attrId/labelId/useYn) 차단.
//  - 입력 형식/길이(name≤64, valuesJson≤1000, defaultVal≤255) 검증은 BE `@Valid` 최종 판정, 400 전파.

import { apiClient } from '@/lib/api/client';

/** 속성 입력 유형 — BE `LabelAttrRequest` inputType 정합. */
export const LABEL_ATTR_INPUT_TYPES = ['SELECT', 'CHECKBOX', 'RADIO', 'NUMBER', 'TEXT'] as const;
export type LabelAttrInputType = (typeof LABEL_ATTR_INPUT_TYPES)[number];

/** 라벨 속성 정의(서버 표현). */
export interface LabelAttrDef {
  attrId: number;
  labelId: number;
  name: string;
  inputType: LabelAttrInputType;
  /** SELECT/CHECKBOX/RADIO 의 선택지 JSON 배열 문자열. 그 외 유형은 null. */
  valuesJson: string | null;
  /** 기본값. 없으면 null. */
  defaultVal: string | null;
  /** 작업 중 값 변경 허용 여부. */
  mutable: 'Y' | 'N';
  sortNo: number;
  useYn: 'Y' | 'N';
}

/**
 * 속성 정의 생성/수정 요청 본문 — 허용 필드만 명시(Mass Assignment 방어).
 * attrId/labelId/useYn 등 서버 관리 필드는 포함하지 않는다.
 */
export interface LabelAttrUpsert {
  name: string;
  inputType: LabelAttrInputType;
  valuesJson: string | null;
  defaultVal: string | null;
  mutable: 'Y' | 'N';
  sortNo: number;
}

/** BE inputType 을 알려진 유형으로 정규화. 미지의 값은 TEXT 로 안전 폴백. */
function normalizeInputType(raw: unknown): LabelAttrInputType {
  return (LABEL_ATTR_INPUT_TYPES as readonly string[]).includes(String(raw))
    ? (String(raw) as LabelAttrInputType)
    : 'TEXT';
}

/** BE 응답 1건을 LabelAttrDef 로 정규화(누락/이상값 안전 폴백). */
function normalizeAttr(a: LabelAttrDef): LabelAttrDef {
  return {
    attrId: Number(a.attrId),
    labelId: Number(a.labelId),
    name: String(a.name ?? ''),
    inputType: normalizeInputType(a.inputType),
    valuesJson: a.valuesJson ?? null,
    defaultVal: a.defaultVal ?? null,
    mutable: a.mutable === 'N' ? 'N' : 'Y',
    sortNo: Number.isFinite(Number(a.sortNo)) ? Number(a.sortNo) : Number.MAX_SAFE_INTEGER,
    useYn: a.useYn === 'N' ? 'N' : 'Y',
  };
}

/** 특정 라벨의 속성 정의 목록 조회. 비배열 응답은 빈 배열로 방어. */
export async function fetchLabelAttrs(labelId: number): Promise<LabelAttrDef[]> {
  const res = await apiClient.get<LabelAttrDef[]>(`/manage/labels/${labelId}/attrs`);
  const data = res.data;
  if (!Array.isArray(data)) return [];
  return data.map(normalizeAttr);
}

/** 속성 정의 생성 (POST → 201). 생성 결과를 정규화해 반환. */
export async function createLabelAttr(
  labelId: number,
  body: LabelAttrUpsert,
): Promise<LabelAttrDef> {
  const res = await apiClient.post<LabelAttrDef>(`/manage/labels/${labelId}/attrs`, body);
  return normalizeAttr(res.data);
}

/** 속성 정의 수정 (PUT → 200). 수정 결과를 정규화해 반환. */
export async function updateLabelAttr(
  labelId: number,
  attrId: number,
  body: LabelAttrUpsert,
): Promise<LabelAttrDef> {
  const res = await apiClient.put<LabelAttrDef>(`/manage/labels/${labelId}/attrs/${attrId}`, body);
  return normalizeAttr(res.data);
}

/** 속성 정의 삭제 (DELETE → 204, 본문 없음). */
export async function deleteLabelAttr(labelId: number, attrId: number): Promise<void> {
  await apiClient.delete(`/manage/labels/${labelId}/attrs/${attrId}`);
}

// ── 라벨 인스턴스(LS_DATA_LBL) 속성값 ─────────────────────────────────────────
//
// BE 계약 (REVIEWER/WORKER):
//   GET /v1/labels/{lblSn}/attrs   (List<LabelAttrValueResponse> — 현재 저장값, 선택지 미포함)
//   PUT /v1/labels/{lblSn}/attrs   ({ values: [{attrId, value}] } — (lblSn,attrId) UNIQUE upsert)
//
// 보안: upsert 본문은 {attrId,value} 만 전송(Mass Assignment 방어). value 는 문자열 —
//       CHECKBOX 다중선택은 JSON 배열 문자열로 직렬화해 저장하고, 로드 시 parseValues 로 복원.

/** 라벨 인스턴스의 속성 저장값(서버 표현). 선택지(valuesJson)는 정의 조회로만 제공됨. */
export interface LabelAttrValue {
  attrId: number;
  name: string;
  inputType: LabelAttrInputType;
  value: string;
}

/** BE 값 응답 1건을 LabelAttrValue 로 정규화(누락/이상값 안전 폴백). */
function normalizeValue(v: LabelAttrValue): LabelAttrValue {
  return {
    attrId: Number(v.attrId),
    name: String(v.name ?? ''),
    inputType: normalizeInputType(v.inputType),
    value: v.value == null ? '' : String(v.value),
  };
}

/** 특정 라벨 인스턴스(lblSn)의 속성 저장값 목록 조회. 비배열 응답은 빈 배열로 방어. */
export async function getLabelAttrValues(lblSn: number): Promise<LabelAttrValue[]> {
  const res = await apiClient.get<LabelAttrValue[]>(`/labels/${lblSn}/attrs`);
  const data = res.data;
  if (!Array.isArray(data)) return [];
  return data.map(normalizeValue);
}

/** 속성값 upsert (PUT → 200). 허용 필드({attrId,value})만 전송. */
export async function putLabelAttrValues(
  lblSn: number,
  values: { attrId: number; value: string }[],
): Promise<void> {
  await apiClient.put(`/labels/${lblSn}/attrs`, { values });
}
