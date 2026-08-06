import { cn } from '@/lib/cn';

type ProgressTone = 'primary' | 'success' | 'warning' | 'danger';
type ProgressSize = 'sm' | 'md';

export interface ProgressBarProps {
  value: number;
  tone?: ProgressTone;
  size?: ProgressSize;
  showLabel?: boolean;
  className?: string;
}

const TONE_CLASSES: Record<ProgressTone, string> = {
  primary: 'bg-primary-500',
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-danger',
};

const SIZE_CLASSES: Record<ProgressSize, string> = {
  sm: 'h-1.5',
  md: 'h-2.5',
};

/**
 * mock UX 정합 — 진행률 바 컴포넌트 (라벨링/검수 작업 진행률 등에 사용).
 * 0–100 범위 외 값은 자동 클램핑.
 */
export function ProgressBar({
  value,
  tone = 'primary',
  size = 'md',
  showLabel = false,
  className,
}: ProgressBarProps) {
  const clamped = Math.min(100, Math.max(0, Number.isFinite(value) ? value : 0));

  return (
    <div className={cn('flex items-center gap-2', className)}>
      <div
        className={cn(
          'flex-1 bg-gray-200 rounded-full overflow-hidden',
          SIZE_CLASSES[size],
        )}
        role="progressbar"
        aria-valuenow={clamped}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <div
          className={cn(
            'h-full rounded-full transition-all duration-300',
            TONE_CLASSES[tone],
          )}
          style={{ width: `${clamped}%` }}
        />
      </div>
      {showLabel && (
        // 퍼센트 수치 라벨 = ladder `label`(14px). 크기는 구 `text-xs` 와 동일.
        <span className="text-label font-medium text-gray-600 tabular-nums w-10 text-right">
          {clamped}%
        </span>
      )}
    </div>
  );
}

export default ProgressBar;
