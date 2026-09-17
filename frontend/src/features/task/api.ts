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
 * 배정 해제 — BE: DELETE /api/v1/assignments/{assignmentId} (검수자 이상).
 * [@design API-259] [@design AC-1123] [@design ADR-069]
 *
 * <p>★<b>재배정과 뜻이 다르다</b> — 재배정은 담당을 바꾸고 해제는 <b>배정을 없앤다</b>.
 * 배정표에 상태 칸이 없어 행을 지우며, 되돌리려면 <b>다시 배정</b>한다(해제 취소 창구는 없다).
 *
 * <p>★<b>작업 결과(라벨·그 이력)는 지우지 않는다</b> — 배정만 푸는 것이 「해제」의 뜻이다.
 * 누가 언제 무엇을 풀었는지는 작업 이벤트 원장에 남는다.
 *
 * <p>★<b>사유를 받지 않는다</b> — 되돌리기 쉽고(다시 배정) 작업 결과가 보존되기 때문이다.
 * 「감추는 쪽만 사유를 남긴다」는 원칙이 제외·복원·해제 셋에 같게 적용된다.
 *
 * <p>★검수 단계에 들어간 배정(검수 대기·검수 중·승인)은 <b>409</b>(`ASSIGNMENT_SUBMITTED`)다 —
 * 배정이 그 워크플로의 전제라 풀면 검수 흐름이 주인 없는 상태가 된다. <b>반려는 그 셋에 들지
 * 않아 해제가 열린다</b>. 화면은 `unassignEligibility` 로 <b>미리</b> 비활성화해, 눌러서
 * 물리쳐진 뒤에야 아는 동선을 없앤다.
 *
 * <p><b>204 No Content</b> 라 응답 본문이 없다(반환값 없음).
 *
 * 보안: assignmentId 는 숫자 path 파라미터로만 전달 — 문자열 직접 연결 없음.
 * 권한(검수자 이상)·상태는 BE 가 403/409 로 강제한다.
 */
export function unassignTask(assignmentId: number): Promise<void> {
  return apiClient.delete(`/assignments/${assignmentId}`).then(() => undefined);
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
