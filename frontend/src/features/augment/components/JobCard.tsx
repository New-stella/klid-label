import { useNavigate } from 'react-router-dom';

import { StatusBadge, type BadgeStatus } from '@/components/common/StatusBadge';
import { resolutionDerivativeLabel } from '@/features/video/types';
import { KRDS_FOCUS } from '@/lib/focusRing';

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

/**
 * 잡 집계 상태의 표시 문구 보정.
 *
 * BE 의 `COMPLETED` 는 "그룹의 모든 항목이 **종료 상태**(채택/반려/**취소**)" 라는 뜻이지 성공이
 * 아니다. 그래서 취소로 종결된 잡도 `COMPLETED` 로 내려온다 — BE 는 이 enum 을 확장하지 않기로
 * 확정했으므로(취소 상태 신설·집계 변경 없음) **표시 축에서** 성공을 단정하지 않는 중립 문구로
 * 보정한다. 상세(항목별 채택/반려/취소)는 결과 화면에서 확인한다.
 */
const statusLabelOverride: Partial<Record<AugmentJobStatus, string>> = {
  COMPLETED: '처리 종료',
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

  // 해상도 파생(SFR-06-03) — 증강 위탁 3종과 구분해 별도 뱃지로 노출한다.
  // 검수(채택/반려) 액션 없음: 파생영상은 자체 검수 파이프라인(PENDING)을 타므로
  // 이 이력 카드에서 증강처럼 승인/반려 대상이 아니다(비-검수 렌더).
  const resolutionTypes = job.resolutionTypes ?? [];
  const hasResolution = resolutionTypes.length > 0;

  return (
    <button
      type="button"
      onClick={handleOpen}
      data-testid={`job-card-${job.jobId}`}
      data-status={job.status}
      className={`flex flex-col gap-2 rounded border border-border bg-white p-3 text-left shadow-sm transition-colors duration-100 hover:border-accent ${KRDS_FOCUS}`}
    >
      <div className="flex items-center justify-between">
        <span className="text-section-title text-primary">{job.cctvName}</span>
        <StatusBadge
          status={statusBadgeMap[job.status]}
          label={statusLabelOverride[job.status]}
        />
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
        {resolutionTypes.map((code) => (
          <span
            key={`resl-${code}`}
            data-testid={`job-card-resolution-${job.jobId}-${code}`}
            className="rounded border border-info/30 bg-info/10 px-2 py-0.5 text-sub text-info-700"
          >
            {resolutionDerivativeLabel(code)}
          </span>
        ))}
        {/* 증강 없이 해상도 파생만 있는 잡: '파생' 구분자를 노출해 증강 검수 완료로 오인 방지 */}
        {hasResolution && job.types.length === 0 && (
          <span
            data-testid={`job-card-derivative-tag-${job.jobId}`}
            className="rounded bg-bgLight px-2 py-0.5 text-sub text-neutral"
          >
            파생
          </span>
        )}
      </div>
      <div className="flex items-center justify-between text-sub text-neutral">
        <span>영상 {job.videoCount.toLocaleString('ko-KR')}건</span>
        <span>{new Date(job.requestedAt).toLocaleString('ko-KR')}</span>
      </div>
    </button>
  );
}
