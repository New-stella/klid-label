import type { AugmentJob, AugmentDecision } from '../../api/types';
import { id, daysAgo, rangeInt, range } from './_helpers';

const AUG_TYPES: ('WINTER' | 'NIGHT' | 'RAIN' | 'RESOLUTION')[] = ['WINTER', 'NIGHT', 'RAIN', 'RESOLUTION'];
const STATUSES: AugmentJob['status'][] = [
  'COMPLETED', 'COMPLETED', 'COMPLETED',
  'PROCESSING', 'PROCESSING',
  'PENDING', 'PENDING',
  'FAILED',
];

// SFR-07 — COMPLETED 잡 인덱스(0,1,2)별 결정 분포
// idx 0 → ACCEPTED, idx 1 → REJECTED, idx 2 → PENDING
const COMPLETED_DECISIONS: AugmentDecision[] = ['ACCEPTED', 'REJECTED', 'PENDING'];

export let augmentJobs: AugmentJob[] = range(8).map((i) => {
  const status = STATUSES[i];
  const typeCount = rangeInt(1, 3, i * 7);
  const types = AUG_TYPES.slice(0, typeCount) as ('WINTER' | 'NIGHT' | 'RAIN' | 'RESOLUTION')[];
  const progress = status === 'COMPLETED' ? 100
    : status === 'PROCESSING' ? rangeInt(10, 90, i * 11)
    : status === 'FAILED' ? rangeInt(10, 60, i * 13)
    : 0;

  // SFR-07 — 결정 상태: COMPLETED만 분포(ACCEPTED/REJECTED/PENDING), 그 외 모두 PENDING
  let decision: AugmentDecision = 'PENDING';
  let decisionAt: string | undefined;
  let decisionBy: string | undefined;
  let decisionReason: string | undefined;
  if (status === 'COMPLETED') {
    decision = COMPLETED_DECISIONS[i % COMPLETED_DECISIONS.length] ?? 'PENDING';
    if (decision !== 'PENDING') {
      decisionAt = daysAgo(rangeInt(0, 5, i * 23));
      decisionBy = 'user-0002';
      if (decision === 'REJECTED') {
        decisionReason = '라벨 무결성 부족 — 재증강 필요';
      }
    }
  }

  return {
    id: id('aug', i),
    videoIds: [`video-${String((i * 3 + 1)).padStart(4, '0')}`, `video-${String((i * 3 + 2)).padStart(4, '0')}`],
    types,
    status,
    progress,
    labelIntegrity: rangeInt(92, 99, i * 17),
    createdAt: daysAgo(rangeInt(1, 30, i * 19)),
    decision,
    decisionAt,
    decisionBy,
    decisionReason,
  };
});

export function addAugmentJob(job: AugmentJob): void {
  augmentJobs = [job, ...augmentJobs];
}

export function findAugmentJob(jobId: string): AugmentJob | undefined {
  return augmentJobs.find((j) => j.id === jobId);
}
