import { useEventTypeLabels } from '@/features/eventType/hooks';
import { cn } from '@/lib/cn';
import { labelOf } from '@/lib/eventTypeLabel';

interface EventTypeBadgeProps {
  /**
   * 영상/작업의 **EV-코드**(상세 코드, 예 EV01000102) 또는 이미 해석된 한글 라벨(eventName)을 받는다.
   * 내부적으로 useEventTypeLabels() 의 **EV-코드→라벨 맵**으로 해석하므로,
   * 호출부는 반드시 EV-코드 또는 한글 라벨을 넘겨야 한다.
   *
   * - 코드(EV01000102 등) → EV-코드 맵으로 한글 카테고리명 변환 후 색상 매핑
   * - 한글 라벨(eventName) → 맵에 없으면 원문 그대로 색상 매핑
   *
   * ⚠️ **categoryKey(예 "020002")는 넘기지 말 것** — EV-코드 맵에 없어 원문이 그대로 노출된다.
   *    categoryKey 를 표시해야 하는 화면(프리셋 등)은 useEventTypes() 의
   *    categoryKey→label 맵으로 **선변환 후** 한글 라벨을 넘겨야 한다.
   */
  eventType: string;
  size?: 'sm' | 'md';
  className?: string;
}

// 카테고리 한글명 → 색상 (cosmetic). 미매핑 라벨은 회색 폴백 — 색상은 표시 보조용일 뿐.
const EVENT_COLORS: Record<string, string> = {
  쓰러짐: 'bg-purple-100 text-purple-700',
  폭력: 'bg-red-100 text-red-700',
  싸움: 'bg-red-100 text-red-700',
  교통사고: 'bg-blue-100 text-blue-700',
  '이상행동(유괴)': 'bg-amber-100 text-amber-700',
  '납치(유괴)': 'bg-amber-100 text-amber-700',
  침수: 'bg-cyan-100 text-cyan-700',
  '침수(범람)': 'bg-cyan-100 text-cyan-700',
  산불: 'bg-rose-100 text-rose-700',
  화재: 'bg-rose-100 text-rose-700',
  산사태: 'bg-orange-100 text-orange-700',
  파손: 'bg-slate-100 text-slate-700',
  흉기소지: 'bg-red-100 text-red-700',
};

const SIZE_CLASSES = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
} as const;

/**
 * 이벤트 색상 뱃지 — 라벨 맵(useEventTypeLabels) 기반 코드→한글 변환 후 색상 매핑.
 * 보안: 라벨은 텍스트 노드로만 렌더 (XSS 방지).
 */
export function EventTypeBadge({ eventType, size = 'sm', className }: EventTypeBadgeProps) {
  const { data: labelMap } = useEventTypeLabels();
  // 빈 입력은 빈 뱃지로 유지 (labelOf 의 '-' 폴백 회피).
  const label = eventType ? labelOf(labelMap, eventType) : '';
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
