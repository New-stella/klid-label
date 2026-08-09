import { AlertTriangle, CheckCircle2, ClipboardCheck, Clock, Loader2, XCircle } from 'lucide-react';
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
  | 'DEIDENT_FAILED'
  // Phase 7b — 검수 승인 이후 라벨/메타 수정으로 재검토가 필요한 영상(V177 REVLT_YN).
  // 워크플로 상태(COMPLETED 등)와 **나란히** 표시하는 보조 배지 — 대체가 아니다.
  | 'NEEDS_RECHECK';

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
//  - 진행/처리 = 스피너(Loader2)     · 검수중 = 클립보드체크(ClipboardCheck)  · 대기 = 시계(Clock)
// UI/UX §3.4 9종 상태 — mock modern blue tone (soft tonal pill) + BE alias
// ⚠ 2026-08-08: info/success/warning/danger 텍스트는 DEFAULT 가 아니라 각 스케일의 700
//   단계를 쓴다 — DS-001 정본 값으로 교체하며 bg-{color}/10 위 텍스트 대비가 3.9~4.1:1 로
//   AA(4.5:1) 미달이 됐다(WCAG 상대휘도 실측). {color}-700 은 같은 스케일의 이미 정의된
//   단계라 새 색을 만들지 않고도 6.8~8.2:1 대로 회복한다. 배경(bg-{color}/10)은 그대로
//   DEFAULT 톤을 쓴다.
const statusConfig: Record<string, StatusConfig> = {
  BATCH_PROCESSING: {
    label: '배치 처리중',
    className: 'bg-info/10 text-info-700',
    icon: Loader2,
    spin: true,
  },
  BATCH_COMPLETED: {
    label: '배치 완료',
    className: 'bg-success/10 text-success-700',
    icon: CheckCircle2,
  },
  BATCH_FAILED: { label: '배치 실패', className: 'bg-danger/10 text-danger-700', icon: XCircle },
  PENDING: { label: '대기', className: 'bg-gray-100 text-gray-600', icon: Clock },
  MARKING_READY: { label: '마킹 대기', className: 'bg-info/10 text-info-700', icon: Clock },
  IN_PROGRESS: {
    label: '작업중',
    className: 'bg-info/10 text-info-700',
    icon: Loader2,
    spin: true,
  },
  PROCESSING: {
    label: '처리중',
    className: 'bg-info/10 text-info-700',
    icon: Loader2,
    spin: true,
  },
  REVIEW_PENDING: { label: '검수요청', className: 'bg-warning/10 text-warning-700', icon: Clock },
  // KRDS 예외: '검수중' purple 은 범주 구분색(성공/실패/경고 어디에도 속하지 않는 별도 상태) — 토큰 획일화 제외.
  REVIEWING: { label: '검수중', className: 'bg-purple-100 text-purple-700', icon: ClipboardCheck },
  IN_REVIEW: { label: '검수중', className: 'bg-purple-100 text-purple-700', icon: ClipboardCheck },
  COMPLETED: { label: '완료', className: 'bg-success/10 text-success-700', icon: CheckCircle2 },
  APPROVED: { label: '승인', className: 'bg-success/10 text-success-700', icon: CheckCircle2 },
  REJECTED: { label: '반려', className: 'bg-danger/10 text-danger-700', icon: XCircle },
  FAILED: { label: '실패', className: 'bg-danger/10 text-danger-700', icon: XCircle },
  // Phase 3 — 비식별 처리 상태 (deidentStatus 기반). 진행중=정보(파랑), 실패=경고(빨강).
  DEIDENT_IN_PROGRESS: {
    label: '비식별 진행중',
    className: 'bg-info/10 text-info-700',
    icon: Loader2,
    spin: true,
  },
  DEIDENT_FAILED: { label: '비식별 실패', className: 'bg-danger/10 text-danger-700', icon: XCircle },
  // Phase 7b — REVIEW_PENDING(검수요청)과 같은 warning 톤을 재사용(신규 색 미도입).
  NEEDS_RECHECK: {
    label: '재검토 필요',
    className: 'bg-warning/10 text-warning-700',
    icon: AlertTriangle,
  },
};

// 매핑에 없는 코드(BE alias 등)는 회색 톤 + 시계 아이콘으로 폴백하고 **라벨은 status 원문**을
// 그대로 보여준다. 그래서 폴백 설정에는 라벨을 넣지 않는다(넣으면 원문이 가려진다).
const FALLBACK: Omit<StatusConfig, 'label'> = {
  className: 'bg-gray-100 text-gray-600',
  icon: Clock,
};

export function StatusBadge({ status, label, className }: StatusBadgeProps) {
  const cfg = statusConfig[status];
  const tone = cfg ?? FALLBACK;
  // ⚠ `??` 로 이으면 안 된다 — 빈 문자열은 null/undefined 가 아니라 그대로 통과해
  //   매핑 밖 상태에서 아이콘만 있고 텍스트가 없는 배지가 된다(구 FALLBACK.label='' 결함).
  const text = label || cfg?.label || status;
  const Icon = tone.icon;
  return (
    <span
      data-status={status}
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-sub font-medium',
        tone.className,
        className,
      )}
    >
      <Icon className={cn('h-3 w-3 shrink-0', tone.spin && 'animate-spin')} aria-hidden="true" />
      {text}
    </span>
  );
}
