// 이벤트 타입 도메인 API — BE: /api/v1/event-types (Phase 2/4a 완료, 호출만).
//
// apiClient 응답 인터셉터가 ApiResponse<T> 래퍼를 언랩하므로 `.data` 만 추출한다.

import { apiClient } from '@/lib/api/client';

import type { EventTypeLabelMap, EventTypeOption } from './types';

/** 필터 드롭다운용 카테고리 옵션 9종 (CLCT_YN='Y' AND 대분류≠08). */
export function getEventTypes(): Promise<EventTypeOption[]> {
  return apiClient.get<EventTypeOption[]>('/event-types').then((r) => r.data);
}

/** 전체 EV-코드 → 한글 카테고리명 맵. 미등록 코드는 맵에 없음(소비측 원문 폴백). */
export function getEventTypeLabels(): Promise<EventTypeLabelMap> {
  return apiClient.get<EventTypeLabelMap>('/event-types/labels').then((r) => r.data);
}
