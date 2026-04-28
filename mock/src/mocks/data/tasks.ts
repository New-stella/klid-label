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
