// 이벤트 타입 도메인 API — BE: /api/v1/event-types (호출만).
//
// apiClient 응답 인터셉터가 ApiResponse<T> 래퍼를 언랩하므로 `.data` 만 추출한다.

import { apiClient } from '@/lib/api/client';

import type { EventTypeLabelMap, EventTypeOption } from './types';

/** 필터 드롭다운용 이벤트유형 옵션 (등록된 유형 중 수집대상 · 제외 대분류 아님). */
export function getEventTypes(): Promise<EventTypeOption[]> {
  return apiClient.get<EventTypeOption[]>('/event-types').then((r) => r.data);
}

/** 등록된 EV-코드 → 이벤트명 맵. 미등록/이름없음은 맵에 없음(소비측 원문 폴백). */
export function getEventTypeLabels(): Promise<EventTypeLabelMap> {
  return apiClient.get<EventTypeLabelMap>('/event-types/labels').then((r) => r.data);
}
