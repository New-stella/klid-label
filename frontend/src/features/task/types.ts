// 작업 배정 도메인 타입 (BE OpenAPI alias)

import { type AugType } from '@/features/augment/augTypeLabel';

/**
 * 배정 워크플로 상태 — BE `GET /v1/assignments` 의 **응답 status 와 필터 `workStatus` 가 같은 값 집합**이다
 * (BE `AssignmentWorkStatus` allowlist 와 1:1). 그래서 표시용 타입을 그대로 필터 파라미터로 쓴다.
 *
 * ★ board 축({@link WorkStatusParam})과 혼동 금지 — 그쪽은 `IN_PROGRESS` 를 반환하지도 허용하지도 않고
 * 대신 `UNASSIGNED` 를 갖는다(미배정 영상까지 다루므로). 두 축은 값 집합이 다르다.
 */
export const ASSIGNMENT_STATUS_VALUES = [
  'PENDING',
  'IN_PROGRESS',
  'REVIEW_PENDING',
  'COMPLETED',
  'REJECTED',
] as const;

export type AssignmentStatus = (typeof ASSIGNMENT_STATUS_VALUES)[number];

export interface Worker {
  id: number;
  name: string;
  email?: string;
  active: boolean;
}

export interface Task {
  id: number;
  videoId: number;
  cctvName: string;
  workerId: number;
  workerName: string;
  reviewerId?: number;
  reviewerName?: string;
  status: AssignmentStatus;
  assignedAt: string;
  /**
   * 해당 영상의 첫 프레임 SRC_SN (LS_DATA_SRC PK).
   * WORKER 가 "작업" 버튼 클릭 시 /label/{firstSrcSn} 으로 navigate 한다.
   * 프레임이 아직 생성되지 않은 영상이면 undefined.
   */
  firstSrcSn?: number;
  /**
   * 영상 이벤트 정보 (LS_DATA_RAW.EVNT_TYPE_CD 기반).
   * 영상 메타가 없거나 코드값 부재 시 undefined — FE 는 "-" 폴백 표시.
   * WORKER 시각 TaskListPage 가 useVideos 의존 없이 이벤트 컬럼을 채울 수 있도록 BE 에서 enrich.
   */
  eventName?: string;
  eventTypeCd?: string;
  /**
   * 비식별 처리 여부/상태 (BE LS_DATA_RAW.DE_IDENT_YN / deidentStatus).
   * 마킹 진입 차단(AC4) 판정용 — 'Y'/DONE 이 아니면 WORKER 마킹 버튼을 비활성화한다.
   */
  deIdntfYn?: 'Y' | 'N' | 'F';
  deidentStatus?: string;
  /**
   * 증강/해상도 파생 데이터 여부 (BE additive). true 면 작업 목록에 증강 뱃지를 노출한다.
   * 구 응답에 없을 수 있어 optional — 없으면 원본(false)으로 취급한다.
   */
  augmented?: boolean;
  /**
   * 증강 종류 코드 (WINTER/NIGHT/RAIN/RESL_1080P/RESL_720P/RESL_480P).
   * null=종류 미상(뱃지에 '증강'만 표시). augmented=false 면 무시된다.
   */
  augType?: AugType | null;
}

export interface Assignment {
  id: number;
  videoId: number;
  workerId: number;
  status: AssignmentStatus;
  assignedAt: string;
}

/**
 * `GET /v1/assignments` 쿼리 파라미터 (WORKER 작업목록).
 *
 * - `q`/`workStatus`/`eventTypeCd` 는 **서버사이드 필터**다 — 전체 배정 기준으로 걸러지고
 *   `totalElements` 도 그 결과 기준이다(화면에서 다시 거르지 않는다).
 * - 빈 문자열은 절대 넣지 않는다 — 값이 없으면 **키 자체를 생략**한다(조립은 `boardParams.ts` 빌더).
 * - `workerId` 는 REVIEWER 전용 필터이며 WORKER 요청에서는 **서버가 무시**한다(403 아님).
 * - `sort` 는 BE allowlist(regDt/assignedAt/rawDataId/videoId/assignmentId/id) 밖이면 400 이다.
 *   WORKER 화면에는 정렬 UI 가 없어 보내지 않는다 — board 정렬 키(shtDt/rawSn)는 이 allowlist 밖이라
 *   URL 에 남아 있는 값을 그대로 흘려보내면 목록이 400 으로 죽는다.
 */
export interface TaskListParams {
  workerId?: number;
  q?: string;
  workStatus?: AssignmentStatus;
  eventTypeCd?: string;
  page?: number;
  size?: number;
  sort?: string;
}

/**
 * `GET /v1/assignments/event-types` 파라미터.
 *
 * BE 는 목록과 같은 축(workerId/q/workStatus)을 받지만 **FE 는 아무것도 보내지 않는다** — 옵션이
 * 다른 필터로 좁아지면 이미 고른 이벤트유형이 목록에서 사라져 되돌아갈 수 없다(board 와 같은 규약).
 * 조회 범위는 서버가 인가로 고정한다(WORKER = 본인 배정).
 */
export type AssignmentEventTypeParams = Pick<TaskListParams, 'workerId'>;

/**
 * REVIEWER 통합 작업 목록 — BE /v1/tasks/board 응답 1행.
 *
 * 처리 완료 영상 + (optional) LABELER 배정을 LEFT JOIN 한 형태.
 * task 측 필드(workerId/workerName/assignmentId/reviewerId/reviewerName/assignedAt/firstSrcSn) 가
 * 모두 null 인 경우 미배정 영상이며, status 는 'UNASSIGNED' 로 폴백된다.
 *
 * AssignmentStatus 외에 'UNASSIGNED' 값을 갖는 점에 주의 — FE STATUS_BADGE_MAP 매핑은
 * TaskListPage 의 RowStatus(= AssignmentStatus | 'UNASSIGNED') 에 그대로 흘려보낸다.
 */
export interface TaskBoardItem {
  videoId: number;
  cctvName: string | null;
  eventName: string | null;
  eventTypeCd: string | null;
  frameCount: number;
  capturedAt: string | null;
  batchStatus: string | null;
  status: AssignmentStatus | 'UNASSIGNED';
  // LABELER 배정 (left-join)
  assignmentId: number | null;
  workerId: number | null;
  workerName: string | null;
  assignedAt: string | null;
  firstSrcSn: number | null;
  // REVIEWER 배정 (left-join)
  reviewerId: number | null;
  reviewerName: string | null;
  // 비식별 처리 여부/상태 (마킹 진입 차단 판정 — AC4). BE 미전송 시 null/undefined.
  deIdntfYn?: 'Y' | 'N' | 'F' | null;
  deidentStatus?: string | null;
  // 증강/해상도 파생 여부 + 종류 (BE additive) — 작업 목록 증강 뱃지용.
  augmented?: boolean | null;
  augType?: AugType | null;
}

/**
 * BE `GET /v1/tasks/board` 의 **배치 상태 축**(`status`) 허용값 — `LS_DATA_RAW.DATA_STTS_CD` 기반.
 *
 * `UNASSIGNED` 는 "배치 상태 필터를 끄고 미배정 전체" 를 뜻하는 **가상 status** 다.
 * 워크플로 축의 `UNASSIGNED`(= 현재 배치 상태 안에서의 미배정)와 **다른 집합**이므로
 * KPI 카드 클릭은 이 축이 아니라 {@link WorkStatusParam} 축으로 보내야 한다.
 */
export const BATCH_STATUS_PARAMS = {
  COMPLETED: 'COMPLETED',
  UNASSIGNED: 'UNASSIGNED',
  ASSIGNED: 'ASSIGNED',
  PENDING: 'PENDING',
  IN_REVIEW: 'IN_REVIEW',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
} as const;

export type BatchStatusParam =
  (typeof BATCH_STATUS_PARAMS)[keyof typeof BATCH_STATUS_PARAMS];

/**
 * BE `GET /v1/tasks/board` 의 **워크플로 축**(`workStatus`) 허용값.
 *
 * ★ 표시용 상태({@link AssignmentStatus} / RowStatus)와 **일부러 분리**한 서버 전송 전용 union 이다.
 * BE `TaskBoardService.mapBoardStatus` 는 `IN_PROGRESS` 를 **절대 반환하지 않으며**(배정됨 = PENDING),
 * 그 값을 파라미터로 보내면 400 이다. 표시용 타입을 그대로 재사용하면 이 차이가 타입에서 사라진다.
 */
export const WORK_STATUS_PARAMS = {
  UNASSIGNED: 'UNASSIGNED',
  PENDING: 'PENDING',
  REVIEW_PENDING: 'REVIEW_PENDING',
  COMPLETED: 'COMPLETED',
  REJECTED: 'REJECTED',
} as const;

export type WorkStatusParam =
  (typeof WORK_STATUS_PARAMS)[keyof typeof WORK_STATUS_PARAMS];

/**
 * `GET /v1/tasks/board` 쿼리 파라미터.
 *
 * - `status` / `workStatus` 는 **독립 축**이며 AND 결합된다 (필드명을 분리해 혼동을 타입에서 차단).
 * - 빈 문자열은 절대 넣지 않는다 — BE `status=` 는 400 이다. 값이 없으면 **키 자체를 생략**한다
 *   (조립은 `boardParams.ts` 의 빌더가 담당).
 * - `sort` 는 `"{key},{dir}"` 문자열 배열. BE allowlist(regDt/shtDt/rawSn) 밖이거나 4개 이상이면 400 이라
 *   `boardSort.ts` 가 매핑·상한을 강제한다.
 */
export interface TaskBoardParams {
  status?: BatchStatusParam;
  workStatus?: WorkStatusParam;
  q?: string;
  eventTypeCd?: string;
  workerId?: number;
  page?: number;
  size?: number;
  sort?: string[];
}

/** `GET /v1/tasks/board/summary` 파라미터 — ★ workStatus 는 보내지 않는다(카드 자체가 선택지). */
export type TaskBoardSummaryParams = Omit<
  TaskBoardParams,
  'workStatus' | 'page' | 'size' | 'sort'
>;

/** `GET /v1/tasks/board/event-types` 파라미터 — status 축만 반영한다. */
export interface TaskBoardEventTypeParams {
  status?: BatchStatusParam;
}

/**
 * `GET /v1/tasks/board/summary` 응답 — **필터 결과 전체 기준** KPI 집계(현재 페이지가 아니다).
 *
 * 불변식: `total === unassigned + inProgress + reviewPending + completed + rejected`.
 * `inProgress` 는 BoardWorkStatus.PENDING(배정됨·검수 미제출) 집계다 — IN_PROGRESS 값은 존재하지 않는다.
 */
export interface TaskBoardSummary {
  total: number;
  unassigned: number;
  inProgress: number;
  reviewPending: number;
  completed: number;
  rejected: number;
}

/**
 * 이벤트유형 옵션 응답 — ★ 배열이 아니라 **객체**다.
 * `GET /v1/tasks/board/event-types`(REVIEWER)와 `GET /v1/assignments/event-types`(WORKER)가
 * **같은 형태**를 반환하므로 화면이 한 컴포넌트로 두 경로를 다룬다.
 *
 * `truncated=true` 면 `items` 는 전체가 아니다(상한 500 초과 절단) — 화면이 그 사실을 알려야
 * "그 이벤트유형 영상이 없다" 는 오인을 막는다.
 */
export interface EventTypeOptionsResponse {
  items: string[];
  truncated: boolean;
}

export interface AssignTaskRequest {
  workerId: number;
  /** 영상(LS_DATA_RAW) PK 목록 — 1건 이상 필수. */
  rawDataIds: number[];
  /** 옵셔널 — 함께 등록할 REVIEWER 사용자 PK. */
  reviewerId?: number;
}

export interface ReassignTaskRequest {
  workerId: number;
}

/**
 * 작업(영상) 단위 이벤트 타입 — LS_TASK_EVENT_LOG.EVENT_TYPE_CD.
 * SCR-TASK-003 타임라인에서 배정/재배정/검수 제출/승인/반려를 동일 구조로 표현한다.
 */
export type TaskEventType =
  | 'ASSIGN'
  | 'REASSIGN'
  | 'SUBMIT'
  | 'CANCEL_SUBMIT'
  | 'APPROVE'
  | 'REJECT';

/**
 * 배정 이력 한 row — BE `AssignmentHistoryResponse` alias.
 *
 * 이벤트 타입별 사용 필드:
 * - ASSIGN    : actor=배정자(REVIEWER), subject=배정된 작업자
 * - REASSIGN  : actor=재배정자(REVIEWER), subject=새 작업자, prev=이전 작업자
 * - SUBMIT    : actor=subject=작업자(본인 제출)
 * - APPROVE   : actor=검수자(REVIEWER)
 * - REJECT    : actor=검수자(REVIEWER), reason=반려 사유
 */
export interface AssignmentHistory {
  eventSeq: number;
  eventTypeCd: TaskEventType;
  actorUserNo: number | null;
  actorUserName: string | null;
  subjectUserNo: number | null;
  subjectUserName: string | null;
  prevUserNo: number | null;
  prevUserName: string | null;
  reason: string | null;
  occurredAt: string;
}
