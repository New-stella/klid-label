import { cn } from '@/lib/cn';

import type { EventDistribution, EventTypeCd } from '../types';

export interface EventDistributionGridProps {
  data: EventDistribution[];
  className?: string;
}

// 6종 고정 (UI/UX §4-3)
const FIXED_EVENT_TYPES: { code: EventTypeCd; label: string }[] = [
  { code: 'FALL', label: '낙상' },
  { code: 'VIOLENCE', label: '폭력' },
  { code: 'TRAFFIC_ACCIDENT', label: '교통사고' },
  { code: 'ABNORMAL_BEHAVIOR', label: '이상행동' },
  { code: 'FLOOD', label: '침수' },
  { code: 'WILDFIRE', label: '산불' },
];

/** 6종 이벤트 분포 그리드 — 데이터 누락 시에도 6종 고정 렌더 */
export function EventDistributionGrid({ data, className }: EventDistributionGridProps) {
  const map = new Map<EventTypeCd, number>();
  for (const d of data) map.set(d.eventTypeCd, d.count);

  return (
    <ul
      aria-label="이벤트 분포"
      data-testid="event-distribution-grid"
      className={cn('grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6', className)}
    >
      {FIXED_EVENT_TYPES.map((t) => {
        const count = map.get(t.code) ?? 0;
        return (
          <li
            key={t.code}
            data-event-type={t.code}
            className="flex flex-col gap-1 rounded border border-border bg-white p-3"
          >
            <span className="text-sub text-neutral">{t.label}</span>
            <strong className="text-section-title text-primary">
              {count.toLocaleString('ko-KR')}
            </strong>
          </li>
        );
      })}
    </ul>
  );
}
