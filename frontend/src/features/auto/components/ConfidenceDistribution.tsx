import { cn } from '@/lib/cn';

import type { ConfidenceBucket } from '../types';

export interface ConfidenceDistributionProps {
  buckets: ConfidenceBucket[];
  className?: string;
}

const BUCKET_LABELS: Record<ConfidenceBucket['bucket'], { label: string; color: string }> = {
  high: { label: '90+ (높음)', color: 'bg-emerald-500' },
  mid: { label: '70-90 (보통)', color: 'bg-amber-500' },
  low: { label: '70 미만 (낮음)', color: 'bg-rose-500' },
};

/**
 * SCR-AUTO-001 신뢰도 분포 — 3개 구간 (high/mid/low) 히스토그램 카드.
 */
export function ConfidenceDistribution({ buckets, className }: ConfidenceDistributionProps) {
  return (
    <section
      data-testid="confidence-distribution"
      aria-label="신뢰도 분포"
      className={cn('rounded border border-border bg-white p-4', className)}
    >
      <h3 className="mb-3 text-section-title text-primary">신뢰도 분포</h3>
      <ul className="flex flex-col gap-2">
        {buckets.map((b) => {
          const meta = BUCKET_LABELS[b.bucket];
          const ratioPct = Math.round(b.ratio * 100);
          return (
            <li
              key={b.bucket}
              data-testid={`confidence-bucket-${b.bucket}`}
              className="flex items-center gap-3"
            >
              <span className="w-32 text-body text-primary">{meta.label}</span>
              <div className="flex-1 rounded bg-bgLight">
                <div
                  className={cn('h-3 rounded', meta.color)}
                  style={{ width: `${ratioPct}%` }}
                  aria-hidden
                />
              </div>
              <span className="w-24 text-right text-body text-neutral">
                {b.count.toLocaleString('ko-KR')}건 ({ratioPct}%)
              </span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
