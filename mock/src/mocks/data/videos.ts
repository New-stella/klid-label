import type {
  VideoDto,
  BatchStage,
  StageStatus,
  PrivacyType,
  ExportStatus,
} from '../../api/types';
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

/**
 * 배치 파이프라인 stage 순서 — V1.8: VLM이 시계열 메타 단계로 맨 앞.
 * 비식별 처리는 stage에서 제거되어 내보내기 시 옵션으로 호출된다.
 */
const STAGES: BatchStage[] = ['VLM', 'FRAME_EXTRACT', 'YOLO', 'SAM2'];

function buildStages(batchStatus: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED', seed: number): { name: BatchStage; status: StageStatus; progress: number }[] {
  if (batchStatus === 'COMPLETED') {
    return STAGES.map((name) => ({ name, status: 'DONE' as StageStatus, progress: 100 }));
  }
  if (batchStatus === 'PENDING') {
    return STAGES.map((name) => ({ name, status: 'PENDING' as StageStatus, progress: 0 }));
  }
  if (batchStatus === 'FAILED') {
    const failIdx = rangeInt(0, STAGES.length - 1, seed);
    return STAGES.map((name, i) => {
      if (i < failIdx) return { name, status: 'DONE' as StageStatus, progress: 100 };
      if (i === failIdx) return { name, status: 'FAIL' as StageStatus, progress: rangeInt(10, 80, seed + i) };
      return { name, status: 'PENDING' as StageStatus, progress: 0 };
    });
  }
  // PROCESSING
  const progIdx = rangeInt(1, STAGES.length - 1, seed);
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

/**
 * 포털 전송 이력 분포 — 시드 인덱스 기반 결정적 분포.
 * - EXPORTED (이미 내보냄): index 1, 3, 8, 10, 14, 30, 32 — 7건 (~21%)
 * - FAILED (이전 전송 시도 실패): index 6, 18 — 2건 (~6%, 사유: 포털 응답 오류)
 *   + index 5, 12 — 2건 (사유: 비식별 실패 — 시뮬레이션 플래그와 정합)
 * - NEVER (한 번도 안 보냄): 나머지
 * EXPORTED 영상의 exportedAt은 4월 NN일로 고정 시드.
 */
const EXPORTED_INDICES = new Set<number>([1, 3, 8, 10, 14, 30, 32]);
const EXPORT_FAILED_PORTAL_INDICES = new Set<number>([6, 18]);
const EXPORT_FAILED_DEIDENT_INDICES = new Set<number>([5, 12]);

/**
 * 비식별 실패 시뮬레이션 플래그 — mock 전용, 외부 API에 노출되지 않는다.
 * 이 Set에 속한 영상은 deidentify=true 옵션으로 전송 시도할 때 그 시도 한정으로 비식별 실패 처리된다.
 * 영상의 영구 속성이 아니라 시뮬레이션 트리거이므로, 실제 모델로 봤을 때
 * "이 영상은 비식별 처리가 어려운 상태에 있어 다음 시도에서도 같은 실패가 재현된다"는 의미.
 *
 * 핸들러(handlers/export.ts)에서만 참조한다.
 */
export const DEIDENT_FAIL_SIMULATION = new Set<string>([
  'video-0006', // index 5
  'video-0013', // index 12
  'video-0021', // index 20
]);

/**
 * 영상 시드.
 *
 * 인덱스 분포:
 * - 0..14 : COMPLETED (처리 완료) — 작업목록의 base 행
 * - 15..24: PROCESSING
 * - 25..29: PENDING
 * - 30..32: COMPLETED 추가 케이스 (시연용 — 처리 완료 영상 중 task가 없는/검수자 미등록 케이스 보장)
 *
 * 추가 분포 (포털 전송 이력):
 * - EXPORTED: index 1, 3, 8, 10, 14, 30, 32
 * - FAILED (포털 응답 오류): index 6, 18
 * - FAILED (비식별 실패 — 이전 시도 결과): index 5, 12
 *   → DEIDENT_FAIL_SIMULATION 플래그와 정합. 다음 시도에서도 비식별 실패 가능 (다시 선택은 가능).
 * - NEVER: 나머지
 */
export const videos: VideoDto[] = Array.from({ length: 33 }, (_, i) => {
  const privacyType = PRIVACY_DIST[i % PRIVACY_DIST.length];
  let batchStatus: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  if (i < 15) batchStatus = 'COMPLETED';
  else if (i < 25) batchStatus = 'PROCESSING';
  else if (i < 30) batchStatus = 'PENDING';
  else batchStatus = 'COMPLETED'; // 30..32 — 시연용 처리 완료 추가
  const eventType = EVENT_TYPES[i % EVENT_TYPES.length];

  // 포털 전송 이력
  let exportStatus: ExportStatus = 'NEVER';
  let exportedAt: string | undefined;
  let lastExportFailureReason: string | undefined;
  if (EXPORTED_INDICES.has(i)) {
    exportStatus = 'EXPORTED';
    // 2026-04-NN 고정 시드 (1..28 사이 결정적 일자)
    const day = ((i * 7) % 28) + 1;
    exportedAt = `2026-04-${String(day).padStart(2, '0')}T09:30:00.000Z`;
  } else if (EXPORT_FAILED_PORTAL_INDICES.has(i)) {
    exportStatus = 'FAILED';
    lastExportFailureReason = '포털 응답 오류';
  } else if (EXPORT_FAILED_DEIDENT_INDICES.has(i)) {
    exportStatus = 'FAILED';
    lastExportFailureReason = '비식별 실패';
  }

  return {
    id: id('video', i),
    cctvName: `CCTV-${pick(CCTV_LOCATIONS, i * 7)}-${String(i + 1).padStart(3, '0')}`,
    eventType,
    durationSec: rangeInt(30, 300, i * 13),
    recordedAt: daysAgo(rangeInt(1, 90, i * 17)),
    batchStatus,
    privacyType,
    stages: buildStages(batchStatus, i * 3),
    assigneeId: i < 20 ? workerIds[i % workerIds.length] : undefined,
    reviewerId: i < 15 ? reviewerIds[i % reviewerIds.length] : undefined,
    taskStatus: batchStatus === 'COMPLETED' ? pick(TASK_STATUSES, i * 5) : undefined,
    exportStatus,
    exportedAt,
    lastExportFailureReason,
    createdAt: daysAgo(rangeInt(30, 90, i * 19)),
    updatedAt: daysAgo(rangeInt(0, 29, i * 23)),
  };
});
