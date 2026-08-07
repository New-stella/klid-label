import { CheckCircle2, Clock, Loader2, Search, XCircle } from 'lucide-react';
import type { ComponentType } from 'react';

import { cn } from '@/lib/cn';

export interface StageBadgeProps {
  stage: string;
  status?: string;
  size?: 'sm' | 'md';
  className?: string;
}

// mock 정합 — stage(이름)별 라벨
// V1.x 후속: 모두 DONE인 영상은 'COMPLETED' stage로 전달 → '완료' 라벨(초록 톤)
const STAGE_LABEL: Record<string, string> = {
  COMPLETED: '완료',
  FAILED: '실패',
  FRAME_EXTRACT: '프레임추출',
  DEIDENTIFY: '비식별화',
  YOLO: 'AI 탐지',
  SAM2: 'AI 분할',
  VLM_VERIFY: 'VLM',
  VLM: 'VLM',
};

// 배지 = DS-001 ladder `label` 축. md 17px 유지 근거는 EventTypeBadge 주석과 동일.
const SIZE_CLASSES = {
  sm: 'text-label px-2 py-0.5',
  md: 'text-body-md px-2.5 py-1',
} as const;

/**
 * mock 정합 — stage 이름 + status 조합으로 톤 결정.
 * - 완료(COMPLETED) → 초록
 * - 실패(FAILED) → 빨강
 * - VLM(특수 단계) → 보라
 * - 진행 중(IN_PROGRESS) → 파랑
 * - 대기 등 그 외 → 노랑(YOLO/SAM2/프레임추출 등 진행 단계 강조용)
 */
function toneClasses(stage: string, status?: string): string {
  // ⚠ 2026-08-08: text-{color}-700 사용 이유는 StatusBadge.tsx 상단 주석 참조(AA 대비 회복).
  if (status === 'COMPLETED' || status === 'DONE') return 'bg-success/10 text-success-700';
  if (status === 'FAILED' || status === 'FAIL') return 'bg-danger/10 text-danger-700';
  // KRDS 예외: VLM 단계 purple 은 범주 구분색(특수 단계 강조, 상태 의미 아님) — 토큰 획일화 제외.
  if (stage === 'VLM_VERIFY' || stage === 'VLM') return 'bg-purple-100 text-purple-700';
  if (status === 'IN_PROGRESS' || status === 'PROGRESS') return 'bg-info/10 text-info-700';
  return 'bg-warning/10 text-warning-700';
}

type IconType = ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>;

// KRDS: 색만으로 구분 금지 → 톤과 동일 의미의 아이콘을 병기.
function stageIcon(stage: string, status?: string): { Icon: IconType; spin?: boolean } {
  if (status === 'COMPLETED' || status === 'DONE') return { Icon: CheckCircle2 };
  if (status === 'FAILED' || status === 'FAIL') return { Icon: XCircle };
  if (stage === 'VLM_VERIFY' || stage === 'VLM') return { Icon: Search };
  if (status === 'IN_PROGRESS' || status === 'PROGRESS') return { Icon: Loader2, spin: true };
  return { Icon: Clock };
}

export function StageBadge({ stage, status, size = 'sm', className }: StageBadgeProps) {
  const label = STAGE_LABEL[stage] ?? stage;
  const { Icon, spin } = stageIcon(stage, status);
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 font-medium rounded-full',
        toneClasses(stage, status),
        SIZE_CLASSES[size],
        className,
      )}
    >
      <Icon className={cn('h-3 w-3 shrink-0', spin && 'animate-spin')} aria-hidden="true" />
      {label}
    </span>
  );
}

export default StageBadge;
