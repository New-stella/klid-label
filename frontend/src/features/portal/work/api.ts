// 포털 작업 화면의 메타·이벤트 어노테이션 창구 — BE: /api/v1/portal/*
//
//   GET /v1/portal/frames/{srcSn}/meta            → 프레임 메타 Load            (API-234)
//   PUT /v1/portal/frames/{srcSn}/meta            → 프레임 메타 저장(오버레이)  (API-235)
//   GET /v1/portal/videos/{rawSn}/event-annotation → 이벤트 어노테이션 Load     (API-236)
//   PUT /v1/portal/videos/{rawSn}/event-annotation → 이벤트 어노테이션 저장     (API-237)
//
// ★저장처는 <b>화면이 가르지 않는다</b> — 서버가 자산 출처(데이터마트 / 본인 업로드)로 판정한다.
//   화면은 어느 출처든 같은 요청을 보내고 어느 쪽인지 알 필요가 없다.
// ★내부 창구(/v1/videos/**·/v1/frames/**)를 부르지 않는다 — 단방향 불변이 깨진다(types.ts 참조).
//
// 보안: 식별자는 number 로 강제해 경로 조작을 막고, 소유자 스코프(IDOR)는 BE 가 강제한다.
//       응답은 apiClient 인터셉터가 ApiResponse.data 만 언랩해 돌려준다.

// @design API-234 @design API-235 @design API-236 @design API-237

import { apiClient } from '@/lib/api/client';
import type { EventAnnotationPayload } from '@/features/label/api/eventAnnotation';

import type { PortalEventAnnotation, PortalFrameMeta, PortalMetaSaveItem } from './types';

/** 응답의 세 목록이 비어 오거나 누락돼도 화면이 터지지 않게 배열로 정규화한다(값은 지어내지 않는다). */
function normalizeMeta(data: Partial<PortalFrameMeta> | undefined, srcSn: number): PortalFrameMeta {
  return {
    rawSn: Number(data?.rawSn ?? 0),
    srcSn: Number(data?.srcSn ?? srcSn),
    items: Array.isArray(data?.items) ? data.items : [],
    readOnlyMeta: Array.isArray(data?.readOnlyMeta) ? data.readOnlyMeta : [],
    technicalMeta: Array.isArray(data?.technicalMeta) ? data.technicalMeta : [],
  };
}

/** 프레임 메타 Load (API-234). */
export function getPortalFrameMeta(srcSn: number): Promise<PortalFrameMeta> {
  return apiClient
    .get<PortalFrameMeta>(`/portal/frames/${srcSn}/meta`)
    .then((r) => normalizeMeta(r.data, srcSn));
}

/**
 * 프레임 메타 저장 (API-235).
 *
 * ★`items` 에는 <b>사용자가 직접 고친 항목만</b> 담는다 — 자동으로 계산된 값을 그대로 되돌려
 * 보내면 사람의 판정으로 승격된다. 판정은 화면이 하며(`isUserDeterminedMetaValue`) 서버도 같은
 * 것을 독립으로 막는다.
 */
export function savePortalFrameMeta(
  srcSn: number,
  items: PortalMetaSaveItem[],
): Promise<PortalFrameMeta> {
  return apiClient
    .put<PortalFrameMeta>(`/portal/frames/${srcSn}/meta`, { items })
    .then((r) => normalizeMeta(r.data, srcSn));
}

function normalizeAnnotation(
  data: Partial<PortalEventAnnotation> | undefined,
  rawSn: number,
): PortalEventAnnotation {
  return {
    rawSn: Number(data?.rawSn ?? rawSn),
    // 없으면 null 그대로 — 빈 구조체를 지어내면 「아직 아무도 쓰지 않았다」가 사라진다.
    annotation: data?.annotation ?? null,
    overridden: data?.overridden === true,
  };
}

/** 이벤트 어노테이션 Load (API-236). ★비식별 누락 신고 구간에서도 <b>막히지 않는다</b>(저장만 막힌다). */
export function getPortalEventAnnotation(rawSn: number): Promise<PortalEventAnnotation> {
  return apiClient
    .get<PortalEventAnnotation>(`/portal/videos/${rawSn}/event-annotation`)
    .then((r) => normalizeAnnotation(r.data, rawSn));
}

/** 이벤트 어노테이션 저장 (API-237). 영상당 한 벌이며 다시 저장하면 덮어쓴다. */
export function savePortalEventAnnotation(
  rawSn: number,
  annotation: EventAnnotationPayload,
): Promise<PortalEventAnnotation> {
  return apiClient
    .put<PortalEventAnnotation>(`/portal/videos/${rawSn}/event-annotation`, { annotation })
    .then((r) => normalizeAnnotation(r.data, rawSn));
}
