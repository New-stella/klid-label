import { CheckCircle2, Clock, Loader2, Search, XCircle } from 'lucide-react';
import type { ComponentType } from 'react';

import { cn } from '@/lib/cn';

export type BadgeStatus =
  | 'BATCH_PROCESSING'
  | 'BATCH_COMPLETED'
  | 'BATCH_FAILED'
  | 'PENDING'
  | 'MARKING_READY'
  | 'IN_PROGRESS'
  | 'PROCESSING'
  | 'REVIEW_PENDING'
  | 'REVIEWING'
  | 'IN_REVIEW'
  | 'COMPLETED'
  | 'APPROVED'
  | 'REJECTED'
  | 'FAILED'
  | 'DEIDENT_IN_PROGRESS'
  | 'DEIDENT_FAILED';

export interface StatusBadgeProps {
  status: BadgeStatus | string;
  label?: string;
  className?: string;
}

type IconType = ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>;

interface StatusConfig {
  label: string;
  className: string;
  icon: IconType;
  spin?: boolean;
}

// KRDS: 색만으로 상태 구분 금지 → 상태 의미별 아이콘을 색+텍스트와 병기(색맹 접근성).
//  - 완료/승인 = 체크(CheckCircle2)  · 실패/반려 = X(XCircle)
//  - 진행/처리 = 스피너(Loader2)     · 검수중 = 돋보기(Search)  · 대기 = 시계(Clock)
// UI/UX §3.4 9종 상태 — mock modern blue tone (soft tonal pill) + BE alias
const statusConfig: Record<string, StatusConfig> = {
  BATCH_PROCESSING: { label: '배치 처리중', className: 'bg-blue-100 text-blue-700', icon: Loader2, spin: true },
  BATCH_COMPLETED: { label: '배치 완료', className: 'bg-green-100 text-green-700', icon: CheckCircle2 },
  BATCH_FAILED: { label: '배치 실패', className: 'bg-red-100 text-red-700', icon: XCircle },
  PENDING: { label: '대기', className: 'bg-gray-100 text-gray-600', icon: Clock },
  MARKING_READY: { label: '마킹 대기', className: 'bg-blue-100 text-blue-700', icon: Clock },
  IN_PROGRESS: { label: '진행중', className: 'bg-blue-100 text-blue-700', icon: Loader2, spin: true },
  PROCESSING: { label: '처리중', className: 'bg-blue-100 text-blue-700', icon: Loader2, spin: true },
  REVIEW_PENDING: { label: '검수 대기', className: 'bg-yellow-100 text-yellow-700', icon: Clock },
  REVIEWING: { label: '검수중', className: 'bg-purple-100 text-purple-700', icon: Search },
  IN_REVIEW: { label: '검수중', className: 'bg-purple-100 text-purple-700', icon: Search },
  COMPLETED: { label: '완료', className: 'bg-green-100 text-green-700', icon: CheckCircle2 },
  APPROVED: { label: '승인', className: 'bg-green-100 text-green-700', icon: CheckCircle2 },
  REJECTED: { label: '반려', className: 'bg-red-100 text-red-700', icon: XCircle },
  FAILED: { label: '실패', className: 'bg-red-100 text-red-700', icon: XCircle },
  // Phase 3 — 비식별 처리 상태 (deidentStatus 기반). 진행중=정보(파랑), 실패=경고(빨강).
  DEIDENT_IN_PROGRESS: { label: '비식별 진행중', className: 'bg-blue-100 text-blue-700', icon: Loader2, spin: true },
  DEIDENT_FAILED: { label: '비식별 실패', className: 'bg-red-100 text-red-700', icon: XCircle },
};

const FALLBACK: StatusConfig = { label: '', className: 'bg-gray-100 text-gray-600', icon: Clock };

export function StatusBadge({ status, label, className }: StatusBadgeProps) {
  const cfg = statusConfig[status] ?? FALLBACK;
  const Icon = cfg.icon;
  return (
    <span
      data-status={status}
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-sub font-medium',
        cfg.className,
        className,
      )}
    >
      <Icon className={cn('h-3 w-3 shrink-0', cfg.spin && 'animate-spin')} aria-hidden="true" />
      {label ?? cfg.label ?? status}
    </span>
  );
}
