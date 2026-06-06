import { useNavigate } from 'react-router-dom';

import { StatusBadge, type BadgeStatus } from '@/components/common/StatusBadge';

import { type AugmentJob, type AugmentJobStatus, type AugmentType } from '../types';

// 현재 증강 유형은 3종(WINTER/NIGHT/RAIN). 과거 잡 이력에 RESOLUTION 이 남아 있을 수 있어
// 레거시 라벨도 표시하되, 유형 union 에는 포함하지 않는다(신규 요청 경로에서 제외 — SFR-06-03).
const typeLabelMap: Record<AugmentType | 'RESOLUTION', string> = {
  WINTER: '겨울',
  NIGHT: '야간',
  RAIN: '비',
  RESOLUTION: '해상도',
};

const statusBadgeMap: Record<AugmentJobStatus, BadgeStatus> = {
  REQUESTED: 'PENDING',
  IN_PROGRESS: 'IN_PROGRESS',
  COMPLETED: 'COMPLETED',
  FAILED: 'BATCH_FAILED',
};

export interface JobCardProps {
  job: AugmentJob;
}

/**
 * 증강 잡 카드 — 클릭 시 `/augment/result/:jobId` navigate.
 *
 * 보안: jobId는 number 타입 — IDOR 방어는 BE 책임.
 */
export function JobCard({ job }: JobCardProps) {
  const navigate = useNavigate();
  const handleOpen = () => navigate(`/augment/result/${job.jobId}`);

  return (
    <button
      type="button"
      onClick={handleOpen}
      data-testid={`job-card-${job.jobId}`}
      data-status={job.status}
      className="flex flex-col gap-2 rounded border border-border bg-white p-3 text-left shadow-sm transition-colors duration-100 hover:border-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
    >
      <div className="flex items-center justify-between">
        <span className="text-section-title text-primary">{job.cctvName}</span>
        <StatusBadge status={statusBadgeMap[job.status]} />
      </div>
      <div className="flex flex-wrap gap-1">
        {job.types.map((t) => (
          <span
            key={t}
            data-testid={`job-card-type-${job.jobId}-${t}`}
            className="rounded bg-bgLight px-2 py-0.5 text-sub text-neutral"
          >
            {typeLabelMap[t]}
          </span>
        ))}
      </div>
      <div className="flex items-center justify-between text-sub text-neutral">
        <span>영상 {job.videoCount.toLocaleString('ko-KR')}건</span>
        <span>{new Date(job.requestedAt).toLocaleString('ko-KR')}</span>
      </div>
    </button>
  );
}
