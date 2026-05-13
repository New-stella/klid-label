import { eventTypeLabel } from '@/constants/eventTypes';
import { cn } from '@/lib/cn';

import type { EventTypeCd } from '@/features/dashboard/types';

interface EventTypeBadgeProps {
  /**
   * mock 라벨 직접 또는 BE EventTypeCd 코드 모두 허용.
   * - 코드(FALL/VIOLENCE/...) → 한글 라벨로 변환 후 색상 매핑
   * - 한글 라벨 → 그대로 색상 매핑
   */
  eventType: string | EventTypeCd;
  size?: 'sm' | 'md';
  className?: string;
}

// SFR 6종 이벤트 (mock과 동일 색상 매핑) — SoT 한글 라벨 기준
const EVENT_COLORS: Record<string, string> = {
  쓰러짐: 'bg-purple-100 text-purple-700',
  폭력: 'bg-red-100 text-red-700',
  교통사고: 'bg-blue-100 text-blue-700',
  '이상행동(유괴)': 'bg-amber-100 text-amber-700',
  침수: 'bg-cyan-100 text-cyan-700',
  산불: 'bg-rose-100 text-rose-700',
};

const SIZE_CLASSES = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
} as const;

/**
 * mock 정합 — 이벤트 6종 색상 뱃지.
 * BE 코드 또는 한글 라벨 모두 입력 가능. 코드→라벨 변환은 SoT `eventTypeLabel` 사용.
 */
export function EventTypeBadge({ eventType, size = 'sm', className }: EventTypeBadgeProps) {
  // SoT `eventTypeLabel` 은 매핑 없는 값을 입력 그대로 반환하므로 한글 라벨도 안전.
  // 단, 빈 문자열에 대해 '-' 폴백을 회피하기 위해 빈 입력은 그대로 통과.
  const label = eventType ? eventTypeLabel(eventType) : eventType;
  const colorClass = EVENT_COLORS[label] ?? 'bg-gray-100 text-gray-600';

  return (
    <span
      className={cn(
        'inline-flex items-center font-medium rounded-full',
        colorClass,
        SIZE_CLASSES[size],
        className,
      )}
    >
      {label}
    </span>
  );
}

export default EventTypeBadge;
