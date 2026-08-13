import { Pin } from 'lucide-react';
import type { ComponentType } from 'react';

import { cn } from '@/lib/cn';

/**
 * 자유 의미 소형 pill 배지 (UI-111).
 *
 * 공지 목록·상세의 "중요(고정)" 표시와 "발행/작성중" 상태처럼 **도메인 자체 의미**를 갖는
 * 배지에 쓴다.
 *
 * ⚠ **StatusBadge(UI-014)와 역할이 다르다** — 그쪽은 워크플로 코드 축(16종 고정 매핑)이라
 *   코드를 넘기면 라벨이 결정되지만, 이쪽은 라벨을 호출부가 정한다(UI-111 description).
 *   워크플로 상태를 이 컴포넌트로 그리지 말 것 — 매핑이 두 곳으로 갈린다.
 *
 * 색상 대비(WCAG 실측): pinned 8.43:1(AAA) · success 6.99:1(AA) · neutral 7.07:1(AAA).
 * `label` 이 필수인 이유가 여기 있다 — 색상 단독으로 의미를 전달하지 않는다.
 */
export type BadgeVariant = 'pinned' | 'success' | 'neutral';

export interface BadgeProps {
  /** pinned=중요(고정) · success=발행 · neutral=작성중(DRAFT). */
  variant: BadgeVariant;
  /** 배지에 표시할 텍스트. 색상 단독 구분을 피하기 위해 필수다. */
  label: string;
  /**
   * 라벨 앞에 병기할 아이콘. 미지정 시 pinned 는 Pin 아이콘을 기본으로 쓰고
   * 나머지 variant 는 아이콘 없이 렌더한다.
   */
  icon?: ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>;
  className?: string;
}

const VARIANT_CLASSES: Record<BadgeVariant, string> = {
  pinned: 'bg-warning-50 text-warning-700',
  success: 'bg-success-50 text-success-700',
  neutral: 'bg-gray-100 text-gray-700',
};

const DEFAULT_ICONS: Partial<
  Record<BadgeVariant, ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>>
> = {
  pinned: Pin,
};

export function Badge({ variant, label, icon, className }: BadgeProps) {
  const Icon = icon ?? DEFAULT_ICONS[variant];

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-label font-semibold',
        VARIANT_CLASSES[variant],
        className,
      )}
    >
      {Icon && <Icon className="h-3 w-3 shrink-0" aria-hidden="true" />}
      {label}
    </span>
  );
}

export default Badge;
