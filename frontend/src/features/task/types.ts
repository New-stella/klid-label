// 작업 배정 도메인 타입 (BE OpenAPI alias)

import { type AugType } from '@/features/augment/augTypeLabel';

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

export interface TaskListParams {
  workerId?: number;
  page?: number;
  size?: number;
  sort?: string;
}

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

export interface TaskBoardParams {
  status?: string;
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
