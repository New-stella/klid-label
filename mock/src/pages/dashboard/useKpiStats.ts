import type { VideoDto } from '../../api/types';

export interface KpiStats {
  pending: number;
  completed: number;
  myWork: number;
  rejected: number;
}

/** videos 목록에서 KPI 4개 계산 */
export function computeKpiStats(
  videos: VideoDto[],
  currentUserId: string,
  currentRole: 'ADMIN' | 'REVIEWER' | 'WORKER',
): KpiStats {
  const pending = videos.filter((v) => v.batchStatus === 'PENDING').length;
  const completed = videos.filter((v) => v.batchStatus === 'COMPLETED').length;
  const rejected = videos.filter((v) => v.taskStatus === 'REJECTED').length;

  let myWork = 0;
  if (currentRole === 'WORKER') {
    myWork = videos.filter((v) => v.assigneeId === currentUserId).length;
  } else if (currentRole === 'REVIEWER') {
    myWork = videos.filter((v) => v.reviewerId === currentUserId).length;
  } else {
    // ADMIN: count all assigned
    myWork = videos.filter((v) => v.assigneeId !== undefined).length;
  }

  return { pending, completed, myWork, rejected };
}

/** 간단한 시드 기반 delta 값 생성 (±5~25%) */
export function randomDelta(seed: number): { value: string; direction: 'up' | 'down' } {
  const pct = 5 + (seed % 21);
  const direction: 'up' | 'down' = seed % 3 === 0 ? 'down' : 'up';
  return { value: `전월 대비 ${direction === 'up' ? '+' : '-'}${pct}%`, direction };
}
