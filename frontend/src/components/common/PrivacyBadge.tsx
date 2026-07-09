import { Shield, ShieldAlert, ShieldCheck } from 'lucide-react';
import type { ComponentType } from 'react';

import { cn } from '@/lib/cn';

export type PrivacyType = 'PRVC' | 'PSDO' | 'ANONY' | string;

type IconType = ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>;

interface PrivacyBadgeProps {
  privacyType: PrivacyType;
  size?: 'sm' | 'md';
  className?: string;
}

// mock 정합 — 개인정보 등급 색상
const TONE_MAP: Record<string, string> = {
  PRVC: 'bg-red-100 text-red-700',
  PSDO: 'bg-yellow-100 text-yellow-700',
  ANONY: 'bg-gray-100 text-gray-600',
};

const LABEL_MAP: Record<string, string> = {
  PRVC: '개인정보',
  PSDO: '가명처리',
  ANONY: '비식별',
};

// KRDS: 색만으로 구분 금지 → 등급별 아이콘 병기.
//  - PRVC(개인정보, 위험) = ShieldAlert · PSDO(가명, 부분보호) = Shield · ANONY(비식별, 안전) = ShieldCheck
const ICON_MAP: Record<string, IconType> = {
  PRVC: ShieldAlert,
  PSDO: Shield,
  ANONY: ShieldCheck,
};

const SIZE_CLASSES = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
} as const;

/**
 * 영상 개인정보 처리 등급 뱃지.
 * - PRVC (개인정보, 빨강)
 * - PSDO (가명처리, 노랑)
 * - ANONY (비식별, 회색)
 */
export function PrivacyBadge({ privacyType, size = 'sm', className }: PrivacyBadgeProps) {
  const label = LABEL_MAP[privacyType] ?? privacyType;
  const tone = TONE_MAP[privacyType] ?? 'bg-gray-100 text-gray-600';
  const Icon = ICON_MAP[privacyType] ?? Shield;

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 font-medium rounded-full',
        tone,
        SIZE_CLASSES[size],
        className,
      )}
    >
      <Icon className="h-3 w-3 shrink-0" aria-hidden="true" />
      {label}
    </span>
  );
}

export default PrivacyBadge;
