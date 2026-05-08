import type { TaskDto } from '../../api/types';
import { id, daysAgo, rangeInt, pick, range } from './_helpers';

const STATUSES: TaskDto['status'][] = [
  'PENDING', 'PENDING', 'IN_PROGRESS', 'IN_PROGRESS', 'IN_PROGRESS',
  'REVIEW_PENDING', 'REVIEW_PENDING', 'COMPLETED', 'COMPLETED', 'REJECTED',
];

const WORKER_IDS = ['user-0004', 'user-0005', 'user-0006', 'user-0007', 'user-0008'];
const WORKER_NAMES = ['최라벨', '정작업', '강라벨링', '윤어노테', '임태그'];
const REVIEWER_IDS = ['user-0002', 'user-0003'];

export let tasks: TaskDto[] = range(25).map((i) => {
  const status = pick(STATUSES, i * 7);
  const hasAssignee = status !== 'PENDING' || rangeInt(0, 1, i * 3) === 1;
  const workerIdx = i % WORKER_IDS.length;
  const progress = status === 'COMPLETED' ? 100
    : status === 'REVIEW_PENDING' ? rangeInt(90, 99, i * 11)
    : status === 'IN_PROGRESS' ? rangeInt(10, 89, i * 13)
    : status === 'REJECTED' ? rangeInt(50, 85, i * 17)
    : 0;

  return {
    id: id('task', i),
    videoId: id('video', i % 30),
    videoName: `CCTV-강남구-${String(i + 1).padStart(3, '0')} 영상`,
    status,
    assigneeId: hasAssignee ? WORKER_IDS[workerIdx] : undefined,
    assigneeName: hasAssignee ? WORKER_NAMES[workerIdx] : undefined,
    reviewerId: status !== 'PENDING' ? pick(REVIEWER_IDS, i * 5) : undefined,
    progress,
    labelCount: rangeInt(20, 200, i * 23),
    createdAt: daysAgo(rangeInt(5, 60, i * 29)),
    updatedAt: daysAgo(rangeInt(0, 4, i * 31)),
  };
});

export function updateTask(taskId: string, patch: Partial<TaskDto>): TaskDto | undefined {
  const idx = tasks.findIndex((t) => t.id === taskId);
  if (idx === -1) return undefined;
  tasks[idx] = { ...tasks[idx], ...patch };
  return tasks[idx];
}

/**
 * 처리 완료된 영상에 대해 task가 없을 때 신규 task를 생성한다.
 * - id: 현재 task 개수 + 1 기준으로 자동 할당
 * - status: 작업자가 배정되었으므로 IN_PROGRESS로 시작
 * - progress, labelCount: 0으로 시작
 */
export function createTask(input: {
  videoId: string;
  videoName: string;
  assigneeId: string;
  assigneeName?: string;
  reviewerId?: string;
}): TaskDto {
  const nextIndex = tasks.length;
  const now = new Date().toISOString();
  const created: TaskDto = {
    id: id('task', nextIndex),
    videoId: input.videoId,
    videoName: input.videoName,
    status: 'IN_PROGRESS',
    assigneeId: input.assigneeId,
    assigneeName: input.assigneeName,
    reviewerId: input.reviewerId,
    progress: 0,
    labelCount: 0,
    createdAt: now,
    updatedAt: now,
  };
  tasks.push(created);
  return created;
}
