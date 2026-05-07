// 작업 배정 도메인 타입 (BE OpenAPI alias)

export type AssignmentStatus = 'PENDING' | 'IN_PROGRESS' | 'SUBMITTED' | 'COMPLETED' | 'REJECTED';

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
  videoIds: number[];
  workerId: number;
}

export interface ReassignTaskRequest {
  workerId: number;
}
