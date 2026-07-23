// Phase 7 — 라벨 마스터 API 클라이언트.
//
// BE: GET /v1/manage/labels — 활성 라벨 마스터 목록 조회 (REVIEWER + WORKER + PORTAL_USER).
//     POST /v1/manage/labels, PUT /v1/manage/labels/{id}, DELETE /v1/manage/labels/{id} (REVIEWER).
// 응답은 ApiResponse<T> 래퍼에서 client interceptor 가 data 만 꺼내 반환.
//
// 보안:
//  - 조회(GET)는 사용자 입력을 받지 않음 (IDOR 위험 없음).
//  - 생성/수정/삭제는 REVIEWER 권한이 BE(SecurityConfig + @PreAuthorize)에서 강제된다.
//  - 요청 본문은 LabelMasterUpsert(허용 필드만) 로 한정 — Mass Assignment(useYn/labelId 등) 차단.
//  - 입력 형식(name/color/type/sortNo) 검증은 BE `@Valid` 가 최종 판정, 실패 시 400 을 그대로 전파.

import { apiClient } from '@/lib/api/client';

/**
 * 라벨 마스터 카테고리 타입 — BE `LabelMasterRequest` regex(BBOX|POLYGON|POINT|SKELETON)와 정합.
 * - SKELETON: COCO-17 휴먼 포즈 키포인트 카테고리(Phase 2 BE 마스터 허용).
 */
export const LABEL_MASTER_TYPES = ['BBOX', 'POLYGON', 'POINT', 'SKELETON'] as const;
export type LabelMasterType = (typeof LABEL_MASTER_TYPES)[number];

export interface LabelMaster {
  labelId: number;
  name: string;
  /** #RRGGBB 형태 */
  color: string;
  /** BBOX | POLYGON | POINT | SKELETON — BE 검증. 알 수 없는 값은 BBOX 로 폴백. */
  type: LabelMasterType;
  sortNo: number;
  useYn: 'Y' | 'N';
  /** AI(COCO) 검출 클래스 매핑 — 미매핑이면 null. BE `dtctTypeCd`(DTCT_TYPE_CD). */
  dtctTypeCd: string | null;
}

/**
 * 라벨 마스터 생성/수정 요청 본문 — 허용 필드만 명시(Mass Assignment 방어).
 * labelId/useYn 등 서버 관리 필드는 포함하지 않는다.
 */
export interface LabelMasterUpsert {
  name: string;
  /** #RRGGBB 형태 */
  color: string;
  type: LabelMasterType;
  sortNo: number;
  /**
   * AI(COCO) 검출 클래스 매핑 — 선택(미지정=null=미매핑). 지정 시 COCO 80 클래스명(영문).
   * 값 검증(allowlist)·중복 매핑 409 는 BE 가 최종 판정(신뢰 경계). FE 는 select 로 입력을 제한한다.
   */
  dtctTypeCd?: string | null;
}

/**
 * AI 탐지 후보 — 활성 라벨 마스터 + COCO 검출 매핑 여부.
 * 라벨링 화면 'AI 탐지' 팝업이 소비한다. BE `DetectCandidateResponse` 와 정합.
 */
export interface DetectCandidate {
  labelId: number;
  name: string;
  color: string;
  type: LabelMasterType;
  /** 매핑된 COCO 클래스명(영문). 미매핑이면 null. */
  dtctTypeCd: string | null;
  /** COCO 검출 매핑 여부(= dtctTypeCd != null). 선택 가능/불가 판단용. */
  mapped: boolean;
}

/** BE 응답 type 을 알려진 카테고리로 정규화. 미지의 값은 BBOX 로 안전 폴백. */
function normalizeMasterType(raw: unknown): LabelMasterType {
  return (LABEL_MASTER_TYPES as readonly string[]).includes(String(raw))
    ? (String(raw) as LabelMasterType)
    : 'BBOX';
}

/** COCO 매핑값 정규화 — 문자열이 비어있지 않으면 그대로, 아니면 null(미매핑). */
function normalizeDtctType(raw: unknown): string | null {
  const s = raw == null ? '' : String(raw).trim();
  return s.length > 0 ? s : null;
}

/** BE 응답 1건을 LabelMaster 로 정규화(누락/이상값 안전 폴백). */
function normalizeMaster(m: LabelMaster): LabelMaster {
  return {
    labelId: Number(m.labelId),
    name: String(m.name ?? ''),
    color: String(m.color ?? '#94A3B8'),
    type: normalizeMasterType(m.type),
    sortNo: Number.isFinite(Number(m.sortNo)) ? Number(m.sortNo) : Number.MAX_SAFE_INTEGER,
    useYn: m.useYn === 'N' ? 'N' : 'Y',
    dtctTypeCd: normalizeDtctType(m.dtctTypeCd),
  };
}

/** BE 후보 응답 1건을 DetectCandidate 로 정규화. mapped 는 dtctTypeCd 존재로 파생(서버값 신뢰 안 함). */
function normalizeCandidate(m: DetectCandidate): DetectCandidate {
  const dtctTypeCd = normalizeDtctType(m.dtctTypeCd);
  return {
    labelId: Number(m.labelId),
    name: String(m.name ?? ''),
    color: String(m.color ?? '#94A3B8'),
    type: normalizeMasterType(m.type),
    dtctTypeCd,
    mapped: dtctTypeCd != null,
  };
}

/**
 * AI 탐지 후보 조회 — 활성 라벨 마스터 + COCO 매핑 여부.
 * GET /manage/labels/detect-candidates (인증된 사용자: REVIEWER + WORKER).
 */
export async function fetchDetectCandidates(): Promise<DetectCandidate[]> {
  const res = await apiClient.get<DetectCandidate[]>('/manage/labels/detect-candidates');
  const data = res.data;
  if (!Array.isArray(data)) return [];
  return data.map(normalizeCandidate);
}

/**
 * 활성 라벨 마스터 목록 조회.
 *
 * BE 응답 예:
 *   [
 *     { "labelId": 1, "name": "사람", "color": "#EF4444", "type": "BBOX", "sortNo": 1, "useYn": "Y" },
 *     ...
 *   ]
 */
export async function fetchLabelMasters(): Promise<LabelMaster[]> {
  const res = await apiClient.get<LabelMaster[]>('/manage/labels');
  const data = res.data;
  if (!Array.isArray(data)) return [];
  // 응답 정규화 — useYn 미정의는 'Y' 로 간주, sortNo NaN 은 최대값으로 후순위 처리.
  return data.map(normalizeMaster);
}

/** 라벨 마스터 생성 (POST /manage/labels → 201). 생성 결과를 정규화해 반환. */
export async function createLabelMaster(body: LabelMasterUpsert): Promise<LabelMaster> {
  const res = await apiClient.post<LabelMaster>('/manage/labels', body);
  return normalizeMaster(res.data);
}

/** 라벨 마스터 수정 (PUT /manage/labels/{labelId} → 200). 수정 결과를 정규화해 반환. */
export async function updateLabelMaster(
  labelId: number,
  body: LabelMasterUpsert,
): Promise<LabelMaster> {
  const res = await apiClient.put<LabelMaster>(`/manage/labels/${labelId}`, body);
  return normalizeMaster(res.data);
}

/** 라벨 마스터 삭제 (DELETE /manage/labels/{labelId} → 204, 본문 없음). */
export async function deleteLabelMaster(labelId: number): Promise<void> {
  await apiClient.delete(`/manage/labels/${labelId}`);
}
