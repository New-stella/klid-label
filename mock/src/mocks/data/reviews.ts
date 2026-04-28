import type { ReviewDto } from '../../api/types';
import { id, daysAgo, rangeInt, range } from './_helpers';

const WORKER_IDS = ['user-0004', 'user-0005', 'user-0006', 'user-0007', 'user-0008'];
const WORKER_NAMES = ['최라벨', '정작업', '강라벨링', '윤어노테', '임태그'];

export let reviews: ReviewDto[] = [
  // 5 PENDING
  ...range(5).map((i) => ({
    id: id('review', i),
    videoId: id('video', i),
    videoName: `CCTV-강남구-${String(i + 1).padStart(3, '0')} 영상`,
    workerId: WORKER_IDS[i % WORKER_IDS.length],
    workerName: WORKER_NAMES[i % WORKER_NAMES.length],
    submittedAt: daysAgo(rangeInt(1, 5, i * 7)),
    labelCount: rangeInt(50, 200, i * 11),
    status: 'PENDING' as const,
  })),
  // 3 IN_REVIEW
  ...range(3).map((i) => ({
    id: id('review', i + 5),
    videoId: id('video', i + 5),
    videoName: `CCTV-서초구-${String(i + 6).padStart(3, '0')} 영상`,
    workerId: WORKER_IDS[(i + 2) % WORKER_IDS.length],
    workerName: WORKER_NAMES[(i + 2) % WORKER_NAMES.length],
    submittedAt: daysAgo(rangeInt(1, 3, (i + 5) * 7)),
    labelCount: rangeInt(30, 150, (i + 5) * 11),
    status: 'IN_REVIEW' as const,
  })),
  // 2 APPROVED
  ...range(2).map((i) => ({
    id: id('review', i + 8),
    videoId: id('video', i + 8),
    videoName: `CCTV-마포구-${String(i + 9).padStart(3, '0')} 영상`,
    workerId: WORKER_IDS[(i + 1) % WORKER_IDS.length],
    workerName: WORKER_NAMES[(i + 1) % WORKER_NAMES.length],
    submittedAt: daysAgo(rangeInt(5, 10, (i + 8) * 7)),
    labelCount: rangeInt(80, 300, (i + 8) * 11),
    status: 'APPROVED' as const,
  })),
  // 2 REJECTED
  ...range(2).map((i) => ({
    id: id('review', i + 10),
    videoId: id('video', i + 10),
    videoName: `CCTV-용산구-${String(i + 11).padStart(3, '0')} 영상`,
    workerId: WORKER_IDS[(i + 3) % WORKER_IDS.length],
    workerName: WORKER_NAMES[(i + 3) % WORKER_NAMES.length],
    submittedAt: daysAgo(rangeInt(3, 8, (i + 10) * 7)),
    labelCount: rangeInt(40, 120, (i + 10) * 11),
    status: 'REJECTED' as const,
    rejectReason: i === 0 ? 'PERSON 라벨 누락 다수 발견. 프레임 20~30 재작업 필요.' : '바운딩박스 좌표 오류. 차량 라벨 크기가 너무 작음.',
    issues: [
      { frameNo: rangeInt(10, 30, (i + 10) * 13), comment: '라벨 좌표 오류' },
      { frameNo: rangeInt(31, 50, (i + 10) * 17), comment: '클래스 잘못 지정' },
      ...(i === 0 ? [{ frameNo: rangeInt(51, 59, (i + 10) * 19), comment: '라벨 누락' }] : []),
    ],
  })),
];

export function updateReview(reviewId: string, patch: Partial<ReviewDto>): ReviewDto | undefined {
  const idx = reviews.findIndex((r) => r.id === reviewId);
  if (idx === -1) return undefined;
  reviews[idx] = { ...reviews[idx], ...patch };
  return reviews[idx];
}
