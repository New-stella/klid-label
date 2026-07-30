// 작업 배정 도메인 API — BE: /api/v1/assignments

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  AssignTaskRequest,
  Assignment,
  AssignmentEventTypeParams,
  AssignmentHistory,
  EventTypeOptionsResponse,
  ReassignTaskRequest,
  Task,
  TaskBoardEventTypeParams,
  TaskBoardItem,
  TaskBoardParams,
  TaskBoardSummary,
  TaskBoardSummaryParams,
  TaskListParams,
} from './types';

/**
 * 배열 파라미터를 `sort=a&sort=b` 로 직렬화한다.
 * axios 기본 직렬화는 `sort[]=a` 라 Spring `Pageable` 이 바인딩하지 못한다.
 */
const REPEAT_ARRAY_PARAMS = { indexes: null } as const;

/**
 * 보안: axios가 자동 URL 인코딩 (XSS/Injection 방지).
 * 사용자 입력은 params/body로만 전달 — 문자열 직접 연결 금지.
 */
export function listTasks(params: TaskListParams) {
  return apiClient
    .get<PageResponse<Task>>('/assignments', { params })
    .then((r) => r.data);
}

/**
 * WORKER 작업목록 이벤트유형 셀렉트 옵션 — BE: /v1/assignments/event-types.
 *
 * 응답은 board 쪽과 **같은** `{ items, truncated }` 객체다. 옵션은 현재 페이지가 아니라 본인 배정
 * **전체** 기준이며, 조회 범위는 서버가 인가로 고정한다(WORKER 는 workerId 를 보내도 무시된다).
 */
export function getAssignmentEventTypes(params: AssignmentEventTypeParams) {
  return apiClient
    .get<EventTypeOptionsResponse>('/assignments/event-types', { params })
    .then((r) => r.data);
}

/**
 * SCR-TASK-001 REVIEWER 통합 작업 목록 — BE: /v1/tasks/board.
 *
 * 처리 완료 영상(LS_DATA_RAW.DATA_STTS_CD) 을 BE 페이징으로 응답하고
 * LABELER/REVIEWER 배정을 LEFT JOIN 방식으로 enrich 한다.
 * 미배정 영상도 함께 노출되며 task 측 필드는 null 로 응답된다.
 *
 * 보안: REVIEWER 권한 필수 (BE @PreAuthorize + Service requireReviewer 이중 가드).
 */
export function listTaskBoard(params: TaskBoardParams) {
  return apiClient
    .get<PageResponse<TaskBoardItem>>('/tasks/board', {
      params,
      paramsSerializer: REPEAT_ARRAY_PARAMS,
    })
    .then((r) => r.data);
}

/**
 * 작업목록 KPI 집계 — BE: /v1/tasks/board/summary.
 *
 * **필터 결과 전체 기준** 집계다(현재 페이지가 아니다). 목록과 별도 요청이라 각 값은 조회 시점 스냅샷.
 * 호출부는 `buildBoardSummaryParams` 로 파라미터를 조립해 workStatus 가 섞이지 않게 한다.
 */
export function getTaskBoardSummary(params: TaskBoardSummaryParams) {
  return apiClient
    .get<TaskBoardSummary>('/tasks/board/summary', { params })
    .then((r) => r.data);
}

/**
 * 이벤트유형 셀렉트 옵션 — BE: /v1/tasks/board/event-types.
 *
 * ★ 응답은 배열이 아니라 `{ items, truncated }` 객체다. `truncated=true` 면 옵션이 전체가 아니므로
 * 화면이 그 사실을 안내해야 한다(그대로 버리면 "그 유형 영상이 없다" 는 조용한 오인이 된다).
 */
export function getTaskBoardEventTypes(params: TaskBoardEventTypeParams) {
  return apiClient
    .get<EventTypeOptionsResponse>('/tasks/board/event-types', { params })
    .then((r) => r.data);
}

export function assignTask(body: AssignTaskRequest) {
  return apiClient.post<Assignment>('/assignments', body).then((r) => r.data);
}

/**
 * 재배정은 PATCH (BE plan 정합 — POST 아님).
 */
export function reassignTask(id: number, body: ReassignTaskRequest) {
  return apiClient.patch<Assignment>(`/assignments/${id}`, body).then((r) => r.data);
}

/**
 * 배정 이력 조회 — REVIEWER 만 접근(서버측 @PreAuthorize 검증).
 * URL 파라미터는 axios 가 안전하게 인코딩한다.
 */
export function getAssignmentHistory(assignmentId: number) {
  return apiClient
    .get<AssignmentHistory[]>(`/assignments/${assignmentId}/history`)
    .then((r) => r.data);
}
