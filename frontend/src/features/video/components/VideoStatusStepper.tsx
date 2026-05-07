import { Check, Loader2 } from 'lucide-react';

import { cn } from '@/lib/cn';

import type { BatchStageInfo } from '../types';

export interface VideoStatusStepperProps {
  stages: BatchStageInfo[];
}

/**
 * 5단계(프레임/비식별/YOLO/SAM2/VLM) 진행 표시기.
 * 각 단계마다 상태 아이콘 + 진행률 바.
 */
export function VideoStatusStepper({ stages }: VideoStatusStepperProps) {
  return (
    <ol
      aria-label="배치 처리 단계"
      className="flex w-full items-stretch gap-2"
    >
      {stages.map((s) => (
        <li
          key={s.stage}
          data-stage={s.stage}
          data-status={s.status}
          className="flex flex-1 flex-col gap-1"
        >
          <div className="flex items-center gap-2">
            <span
              aria-hidden
              className={cn(
                'inline-flex h-5 w-5 items-center justify-center rounded-full border text-[10px]',
                s.status === 'COMPLETED' && 'border-success bg-success/10 text-success',
                s.status === 'IN_PROGRESS' && 'border-accent bg-accent/10 text-accent',
                s.status === 'PENDING' && 'border-border bg-bgLight text-neutral',
                s.status === 'FAILED' && 'border-danger bg-danger/10 text-danger',
              )}
            >
              {s.status === 'COMPLETED' && <Check className="h-3 w-3" aria-hidden />}
              {s.status === 'IN_PROGRESS' && (
                <Loader2 className="h-3 w-3 animate-spin" aria-hidden />
              )}
            </span>
            <span className="text-sub text-primary">{s.label}</span>
            <span className="ml-auto text-sub text-neutral">{s.progressPercent}%</span>
          </div>
          <div
            role="progressbar"
            aria-label={`${s.label} 진행률`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={s.progressPercent}
            className="h-1.5 w-full overflow-hidden rounded bg-bgLight"
          >
            <span
              className={cn(
                'block h-full transition-all',
                s.status === 'COMPLETED' && 'bg-success',
                s.status === 'IN_PROGRESS' && 'bg-accent',
                s.status === 'PENDING' && 'bg-border',
                s.status === 'FAILED' && 'bg-danger',
              )}
              style={{ width: `${Math.max(0, Math.min(100, s.progressPercent))}%` }}
            />
          </div>
        </li>
      ))}
    </ol>
  );
}
