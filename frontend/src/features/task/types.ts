// 작업 배정 도메인 타입 (BE OpenAPI alias)

export type AssignmentStatus = 'PENDING' | 'IN_PROGRESS' | 'REVIEW_PENDING' | 'COMPLETED' | 'REJECTED';

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
}

export interface Assignment {
  id: number;
  videoId: number;
  workerId: number;
  status: AssignmentStatus;
  assignedAt: string;
}

export interface TaskListParams {
  workerId?: number;
  page?: number;
  size?: number;
  sort?: string;
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
