import type { VideoDto, BatchStage, StageStatus, PrivacyType } from '../../api/types';
import { id, daysAgo, rangeInt, pick } from './_helpers';

const EVENT_TYPES = ['쓰러짐', '폭력', '교통사고', '이상행동(유괴)', '침수', '산불'] as const;
const CCTV_LOCATIONS = [
  '강남구 테헤란로',
  '서초구 반포대로',
  '송파구 올림픽로',
  '종로구 세종대로',
  '마포구 홍대입구',
  '영등포구 여의대방로',
  '용산구 한강대로',
  '중구 을지로',
  '성동구 왕십리로',
  '광진구 구의동',
  '동대문구 천호대로',
  '성북구 돌곶이로',
  '노원구 화랑로',
  '도봉구 방학로',
  '은평구 통일로',
] as const;

const STAGES: BatchStage[] = ['FRAME_EXTRACT', 'DEIDENTIFY', 'YOLO', 'SAM2', 'VLM'];

function buildStages(batchStatus: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED', seed: number): { name: BatchStage; status: StageStatus; progress: number }[] {
  if (batchStatus === 'COMPLETED') {
    return STAGES.map((name) => ({ name, status: 'DONE' as StageStatus, progress: 100 }));
  }
  if (batchStatus === 'PENDING') {
    return STAGES.map((name) => ({ name, status: 'PENDING' as StageStatus, progress: 0 }));
  }
  if (batchStatus === 'FAILED') {
    const failIdx = rangeInt(0, 4, seed);
    return STAGES.map((name, i) => {
      if (i < failIdx) return { name, status: 'DONE' as StageStatus, progress: 100 };
      if (i === failIdx) return { name, status: 'FAIL' as StageStatus, progress: rangeInt(10, 80, seed + i) };
      return { name, status: 'PENDING' as StageStatus, progress: 0 };
    });
  }
  // PROCESSING
  const progIdx = rangeInt(1, 4, seed);
  return STAGES.map((name, i) => {
    if (i < progIdx) return { name, status: 'DONE' as StageStatus, progress: 100 };
    if (i === progIdx) return { name, status: 'PROGRESS' as StageStatus, progress: rangeInt(10, 90, seed + i) };
    return { name, status: 'PENDING' as StageStatus, progress: 0 };
  });
}

const PRIVACY_DIST: PrivacyType[] = [
  ...Array(15).fill('PRVC'),
  ...Array(10).fill('PSDO'),
  ...Array(5).fill('ANONY'),
] as PrivacyType[];

const TASK_STATUSES = [
  'BATCH_COMPLETED', 'PENDING', 'IN_PROGRESS', 'REVIEW_PENDING', 'REVIEW', 'COMPLETED', 'REJECTED',
] as const;

const workerIds = ['user-0004', 'user-0005', 'user-0006', 'user-0007', 'user-0008'];
const reviewerIds = ['user-0002', 'user-0003'];

export const videos: VideoDto[] = Array.from({ length: 30 }, (_, i) => {
  const privacyType = PRIVACY_DIST[i];
  const batchStatuses: ('PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED')[] = [
    ...Array(15).fill('COMPLETED'),
    ...Array(10).fill('PROCESSING'),
    ...Array(5).fill('PENDING'),
  ];
  const batchStatus = batchStatuses[i];
  const eventType = EVENT_TYPES[i % EVENT_TYPES.length];

  return {
    id: id('video', i),
    cctvName: `CCTV-${pick(CCTV_LOCATIONS, i * 7)}-${String(i + 1).padStart(3, '0')}`,
    eventType,
    durationSec: rangeInt(30, 300, i * 13),
    recordedAt: daysAgo(rangeInt(1, 90, i * 17)),
    batchStatus,
    privacyType,
    deidentified: privacyType !== 'ANONY' && batchStatus === 'COMPLETED',
    stages: buildStages(batchStatus, i * 3),
    assigneeId: i < 20 ? workerIds[i % workerIds.length] : undefined,
    reviewerId: i < 15 ? reviewerIds[i % reviewerIds.length] : undefined,
    taskStatus: batchStatus === 'COMPLETED' ? pick(TASK_STATUSES, i * 5) : undefined,
    createdAt: daysAgo(rangeInt(30, 90, i * 19)),
    updatedAt: daysAgo(rangeInt(0, 29, i * 23)),
  };
});
