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

// SFR 6종 이벤트 (mock과 동일 색상 매핑)
const EVENT_COLORS: Record<string, string> = {
  쓰러짐: 'bg-purple-100 text-purple-700',
  폭력: 'bg-red-100 text-red-700',
  교통사고: 'bg-blue-100 text-blue-700',
  '이상행동(유괴)': 'bg-amber-100 text-amber-700',
  침수: 'bg-cyan-100 text-cyan-700',
  산불: 'bg-rose-100 text-rose-700',
};

// BE EventTypeCd → 한글 라벨 매핑 (대시보드 EventTypeCd와 호환)
// DB 시드 코드(EVT_ 접두사)와 필터용 약어 코드 모두 지원
const CODE_TO_LABEL: Record<string, string> = {
  // 필터 약어 코드
  FALL: '쓰러짐',
  VIOLENCE: '폭력',
  TRAFFIC_ACCIDENT: '교통사고',
  ABNORMAL_BEHAVIOR: '이상행동(유괴)',
  FLOOD: '침수',
  WILDFIRE: '산불',
  // DB EVT_ 접두사 코드 (LS_DATA_RAW.EVNT_TYPE_CD 실제 값)
  EVT_FALL: '쓰러짐',
  EVT_VIOLENCE: '폭력',
  EVT_ACCIDENT: '교통사고',
  EVT_ABNORMAL: '이상행동(유괴)',
  EVT_FLOOD: '침수',
  EVT_FIRE: '산불',
};

const SIZE_CLASSES = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
} as const;

/**
 * mock 정합 — 이벤트 6종 색상 뱃지.
 * BE 코드 또는 한글 라벨 모두 입력 가능.
 */
export function EventTypeBadge({ eventType, size = 'sm', className }: EventTypeBadgeProps) {
  const label = CODE_TO_LABEL[eventType] ?? eventType;
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
