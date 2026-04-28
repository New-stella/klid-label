import type { WorkerStat, OverallStat } from '../../api/types';
import { daysAgo, rangeInt, range } from './_helpers';

const WORKER_IDS = ['user-0004', 'user-0005', 'user-0006', 'user-0007', 'user-0008', 'user-0009', 'user-0010', 'user-0011'];
const WORKER_NAMES = ['최라벨', '정작업', '강라벨링', '윤어노테', '임태그', '한마킹', '오분류', '서레이블'];

// videos.ts와 동일한 이벤트 타입 (순환 참조 회피를 위해 재선언)
const EVENT_TYPES = ['쓰러짐', '폭력', '교통사고', '이상행동(유괴)', '침수', '산불'] as const;
// 분배 비율 (합 1.0). 마지막 '산불' 항목에서 잔여를 흡수해 합계 정확 일치 보장
const EVENT_RATIOS = [0.22, 0.15, 0.30, 0.12, 0.13, 0.08] as const;

function distributeByEvent(total: number): { eventType: string; count: number }[] {
  const counts = EVENT_RATIOS.map((ratio) => Math.floor(total * ratio));
  const sum = counts.reduce((s, c) => s + c, 0);
  // 마지막 '산불'에서 잔여를 흡수해 합계가 정확히 일치하도록
  counts[counts.length - 1] += total - sum;
  return EVENT_TYPES.map((eventType, i) => ({ eventType, count: counts[i] }));
}

export const workerStats: WorkerStat[] = WORKER_IDS.map((workerId, i) => ({
  workerId,
  workerName: WORKER_NAMES[i],
  completed: rangeInt(20, 150, i * 7),
  inProgress: rangeInt(0, 10, i * 11),
  rejected: rangeInt(0, 15, i * 13),
  labelCount: rangeInt(500, 5000, i * 17),
  autoLabelRate: (rangeInt(50, 80, i * 19)) / 100,
  rejectRate: (rangeInt(0, 15, i * 29)) / 100,
}));

export const dailyCounts = range(30).map((i) => ({
  date: daysAgo(29 - i).substring(0, 10),
  count: rangeInt(50, 500, i * 7),
}));

const IMAGE_COMPLETED = 43250;
const VIDEO_COMPLETED = 1820;

export const overallStat: OverallStat = {
  imageTarget: 100000,
  imageCompleted: IMAGE_COMPLETED,
  videoTarget: 5000,
  videoCompleted: VIDEO_COMPLETED,
  imageByEvent: distributeByEvent(IMAGE_COMPLETED),
  videoByEvent: distributeByEvent(VIDEO_COMPLETED),
  workers: workerStats,
  dailyCounts,
};
