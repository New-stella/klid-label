import { ArrowRight } from 'lucide-react';

import { cn } from '@/lib/cn';

import type { StateChange } from '../types';

export interface StateChangeTimelineProps {
  changes: StateChange[];
  className?: string;
}

/**
 * SCR-AUTO-002 — 외부 자동 감지된 상태 변화 타임라인.
 * V1.7: 외부 시스템이 검출한 상태 전이 (frame x → frame y) 순차 표시.
 */
export function StateChangeTimeline({ changes, className }: StateChangeTimelineProps) {
  if (changes.length === 0) {
    return (
      <section
        data-testid="state-change-timeline"
        aria-label="상태 변화 타임라인"
        className={cn('rounded border border-border bg-white p-4', className)}
      >
        <h3 className="mb-2 text-section-title text-primary">상태 변화</h3>
        <p className="text-sub text-neutral">감지된 상태 변화가 없습니다.</p>
      </section>
    );
  }
  return (
    <section
      data-testid="state-change-timeline"
      aria-label="상태 변화 타임라인"
      className={cn('rounded border border-border bg-white p-4', className)}
    >
      <h3 className="mb-3 text-section-title text-primary">상태 변화</h3>
      <ol className="flex flex-col gap-2">
        {changes.map((c, idx) => (
          <li
            key={`${c.frameNo}-${idx}`}
            data-testid={`state-change-${c.frameNo}`}
            className="flex items-center gap-2 text-body text-primary"
          >
            <span className="rounded bg-bgLight px-2 py-0.5 text-sub text-neutral">
              #{c.frameNo}
            </span>
            <span>{c.fromState}</span>
            <ArrowRight className="h-3 w-3 text-neutral" aria-hidden />
            <span className="font-medium">{c.toState}</span>
          </li>
        ))}
      </ol>
    </section>
  );
}
