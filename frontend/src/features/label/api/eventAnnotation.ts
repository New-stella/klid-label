// Phase 4 — event_annotation(외부 VLM VQA/CoT) API 클라이언트 + 타입.
//
// BE 계약(확정 — 위키 §24.3.1):
//   GET /v1/videos/{rawSn}/event-annotation → ApiResponse<EventAnnotationInfo>  (WORKER·REVIEWER)
//   PUT /v1/videos/{rawSn}/event-annotation  body EventAnnotationPayload → ApiResponse<EventAnnotationInfo>
// 응답은 apiClient interceptor 가 ApiResponse.data 만 언랩해 반환한다.
//
// payload 는 caption/evidence 가 c1..cn 키 객체(BE DTO EventAnnotationPayload 와 필드명·타입 정확 일치).
// 와이어 포맷이 snake_case(@JsonProperty) 이므로 타입도 snake_case 키로 정의해 round-trip 무손실 보존.
//
// 보안: rawSn 은 number 로 강제 — path 조작 불가. any 미사용(unknown/구체타입). API URL 은 axios baseURL(환경변수).

import { apiClient } from '@/lib/api/client';

/** caption 후보(c1..cn): caption_text + Chain-of-Thought 단계(cot, 1·2·3단계 배열). */
export interface CaptionCandidate {
  caption_text?: string;
  cot?: string[];
}

/** evidence 후보(c1..cn): evidence_text + 프레임/객체 근거. */
export interface EvidenceCandidate {
  evidence_text?: string;
  frame_id?: number[];
  obj_id?: string[];
  obj_bbox?: number[][];
  obj_label?: string[];
}

/**
 * event_annotation payload — BE {@code EventAnnotationPayload} 와 정확 일치.
 * caption/evidence 는 후보 키(c1..cn) 객체이며, caption cN 과 evidence cN 은 같은 키로 연결된다.
 */
export interface EventAnnotationPayload {
  /** 이벤트 분류(필수) */
  event_class: string;
  question?: string;
  caption?: Record<string, CaptionCandidate>;
  answer?: string;
  evidence?: Record<string, EvidenceCandidate>;
}

/** BE {@code EventAnnotationInfo} — Entity 직접 노출 없이 payload + 검토 상태. */
export interface EventAnnotationInfo {
  rawSn: number;
  evntAnnoSn: number | null;
  payload: EventAnnotationPayload;
  /** LS_EVNT_ANNO_REVIEW 현재 상태(없으면 null): AUTO_GENERATED/PENDING/APPROVED/REJECTED 등 */
  reviewStatus: string | null;
  regId: string | null;
  mdfcnId: string | null;
}

function normalize(
  data: Partial<EventAnnotationInfo> | undefined,
  rawSn: number,
): EventAnnotationInfo {
  return {
    rawSn: Number(data?.rawSn ?? rawSn),
    evntAnnoSn: data?.evntAnnoSn ?? null,
    payload: data?.payload ?? { event_class: '' },
    reviewStatus: data?.reviewStatus ?? null,
    regId: data?.regId ?? null,
    mdfcnId: data?.mdfcnId ?? null,
  };
}

/** 영상(rawSn) 단위 event_annotation 조회. */
export function getEventAnnotation(rawSn: number): Promise<EventAnnotationInfo> {
  return apiClient
    .get<EventAnnotationInfo>(`/videos/${rawSn}/event-annotation`)
    .then((r) => normalize(r.data, rawSn));
}

/** 영상(rawSn) 단위 event_annotation 저장/수정(upsert). payload 를 그대로 전송한다. */
export function putEventAnnotation(
  rawSn: number,
  payload: EventAnnotationPayload,
): Promise<EventAnnotationInfo> {
  return apiClient
    .put<EventAnnotationInfo>(`/videos/${rawSn}/event-annotation`, payload)
    .then((r) => normalize(r.data, rawSn));
}

/**
 * 영상(rawSn) 단위 event_annotation 검토 승인 — REVIEWER 전용(BE @PreAuthorize).
 * PENDING/AUTO_GENERATED 에서만 성공하며 이미 검토 완료면 BE 가 409 를 반환한다.
 */
export function approveEventAnnotation(rawSn: number): Promise<void> {
  return apiClient
    .post<void>(`/videos/${rawSn}/event-annotation/approve`)
    .then(() => undefined);
}

/**
 * 영상(rawSn) 단위 event_annotation 검토 반려 — REVIEWER 전용. 사유 필수(BE @NotBlank).
 * reason 은 request body 로만 전송되어 경로 조작·인젝션에 노출되지 않는다.
 */
export function rejectEventAnnotation(rawSn: number, reason: string): Promise<void> {
  return apiClient
    .post<void>(`/videos/${rawSn}/event-annotation/reject`, { reason })
    .then(() => undefined);
}
