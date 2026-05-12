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
  status: AssignmentStatus;
  assignedAt: string;
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
  /** 프로젝트 ID. 현재 단일 프로젝트 운영 — 기본 1 사용. */
  pjtId: number;
  workerId: number;
  /** 영상(LS_DATA_RAW) PK 목록 — 1건 이상 필수. */
  rawDataIds: number[];
}

export interface ReassignTaskRequest {
  workerId: number;
}

/**
 * 배정 이력 한 row — BE `AssignmentHistoryResponse` alias.
 * 변경 사유(reason)는 현재 스키마에 컬럼이 없어 null 로 전달된다.
 */
export interface AssignmentHistory {
  hstrySn: number;
  chgTypeCd: 'ASSIGN' | 'REASSIGN';
  prevUserNo: number | null;
  prevUserName: string | null;
  newUserNo: number | null;
  newUserName: string | null;
  reason: string | null;
  chgDt: string;
}
